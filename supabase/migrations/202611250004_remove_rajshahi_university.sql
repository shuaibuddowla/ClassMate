-- Migration: 202611250004_remove_rajshahi_university.sql
-- Description: Completely remove University of Rajshahi (RU / Faculty of Fisheries / Batch 25),
--   its preseeded roster table, its test/live records, and its onboarding/hook branches.
--   Keep ClassMate exclusively focused on Mawlana Bhashani Science and Technology University (MBSTU).

BEGIN;

-- 1. Update create_or_initialize_profile() without RU / preseeded_student_roster
CREATE OR REPLACE FUNCTION classmate.create_or_initialize_profile()
 RETURNS classmate.profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  u auth.users%rowtype;
  result classmate.profiles%rowtype;
  dept uuid;
  v_batch uuid;
  v_inst classmate.institutions%rowtype;
  v_domain text;
  matched text[];
  parsed_student_id text;
  parsed_session smallint;
  v_cr classmate.cr_allowlist%rowtype;
  v_override classmate.batch_overrides%rowtype;
  effective_name text;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;

  v_domain := lower(split_part(u.email, '@', 2));

  select * into v_inst from classmate.institutions
  where (email_domain = v_domain or v_domain like '%.' || email_domain) and is_active
  limit 1;

  if v_inst.id is null and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your @mbstu.ac.bd Google account or an admin-approved teacher Gmail' using errcode='28000';
  end if;

  -- Check if app owner (admin)
  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    matched := regexp_match(lower(u.email), '^([a-z]+)([0-9]{2})([0-9]{3,4})@');
    if matched is not null and v_inst.id is not null then
      select d.id into dept from classmate.departments d
      where d.email_prefix = matched[1] and d.institution_id = v_inst.id and d.is_active limit 1;
      if dept is not null then
        select b.id into v_batch from classmate.batches b
        where b.department_id = dept and b.academic_session = matched[2]::smallint and b.is_active limit 1;
      end if;
      parsed_student_id := upper(matched[1] || matched[2] || matched[3]);
      parsed_session := matched[2]::smallint;
    end if;

    insert into classmate.profiles(id, email, full_name, role, department_id, batch_id, student_id, academic_session, verification_status, institution_id)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', dept, v_batch, parsed_student_id, parsed_session, 'active', v_inst.id)
    on conflict (id) do update set
      role = 'admin',
      verification_status = 'active',
      department_id = coalesce(classmate.profiles.department_id, excluded.department_id),
      batch_id = coalesce(classmate.profiles.batch_id, excluded.batch_id),
      student_id = coalesce(classmate.profiles.student_id, excluded.student_id),
      academic_session = coalesce(classmate.profiles.academic_session, excluded.academic_session),
      institution_id = coalesce(classmate.profiles.institution_id, excluded.institution_id),
      updated_at = now();

  else
    -- Check teacher allowlist
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      if v_inst.id is null then
        select institution_id into v_inst.id from classmate.departments where id = dept;
      end if;

      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status, institution_id)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active', v_inst.id)
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', institution_id = excluded.institution_id, updated_at = now();
    else
      -- Check CR allowlist
      select * into v_cr from classmate.cr_allowlist where email = lower(u.email) and active limit 1;
      if v_cr.email is not null then
        if v_inst.id is null and v_cr.department_id is not null then
          select institution_id into v_inst.id from classmate.departments where id = v_cr.department_id;
        end if;

        insert into classmate.profiles(id, email, full_name, role, department_id, batch_id, student_id, academic_session, verification_status, is_cr, cr_batch_id, institution_id)
        values (
          u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
          'student', v_cr.department_id, v_cr.batch_id, v_cr.student_id, v_cr.academic_session,
          'active', true, v_cr.batch_id, v_inst.id
        )
        on conflict (id) do update set
          role = 'student',
          department_id = excluded.department_id,
          batch_id = excluded.batch_id,
          student_id = excluded.student_id,
          academic_session = excluded.academic_session,
          verification_status = 'active',
          is_cr = true,
          cr_batch_id = excluded.batch_id,
          institution_id = excluded.institution_id,
          updated_at = now();
      else
        -- Check administrative batch override (re-admitted / transfer)
        matched := regexp_match(lower(u.email), '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
        if matched is not null then
          parsed_student_id := upper(matched[1] || matched[2] || matched[3]);
        end if;

        select * into v_override from classmate.batch_overrides
        where (email is not null and lower(trim(email)) = lower(trim(u.email)))
           or (parsed_student_id is not null and upper(trim(student_id)) = parsed_student_id)
        limit 1;

        if v_override.effective_batch_id is not null then
          select department_id, academic_session into dept, parsed_session
          from classmate.batches where id = v_override.effective_batch_id;

          effective_name := coalesce(nullif(trim(u.raw_user_meta_data->>'full_name'), ''), 'Student');

          insert into classmate.profiles(
            id, email, full_name, role, department_id, batch_id,
            student_id, academic_session, profile_source, verification_status,
            is_cr, cr_batch_id, institution_id
          ) values (
            u.id, lower(u.email), effective_name, 'student', dept, v_override.effective_batch_id,
            coalesce(parsed_student_id, v_override.student_id), parsed_session, 'auto', 'active',
            false, null, v_inst.id
          )
          on conflict (id) do update set
            role = 'student',
            full_name = case when classmate.profiles.full_name = '' then excluded.full_name else classmate.profiles.full_name end,
            department_id = excluded.department_id,
            batch_id = excluded.batch_id,
            student_id = coalesce(excluded.student_id, classmate.profiles.student_id),
            academic_session = excluded.academic_session,
            verification_status = 'active',
            profile_source = 'auto',
            institution_id = excluded.institution_id,
            updated_at = now();
        else
          -- Standard MBSTU student onboarding flow
          insert into classmate.profiles(id, email, full_name, role, verification_status, institution_id)
          values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
            'student', 'pending', v_inst.id)
          on conflict (id) do update set role = 'student',
            verification_status = case when classmate.profiles.role <> 'student'
              then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
            department_id = case when classmate.profiles.role <> 'student'
              then null else classmate.profiles.department_id end,
            institution_id = coalesce(classmate.profiles.institution_id, excluded.institution_id),
            updated_at = now();
        end if;
      end if;
    end if;
  end if;

  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$function$;

-- 2. Update complete_student_onboarding() without RU / preseeded_student_roster
CREATE OR REPLACE FUNCTION classmate.complete_student_onboarding(selected_department uuid, entered_student_id text DEFAULT NULL::text, entered_batch_number smallint DEFAULT NULL::smallint, entered_session smallint DEFAULT NULL::smallint)
 RETURNS classmate.profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  p classmate.profiles%rowtype;
  d classmate.departments%rowtype;
  matched text[];
  derived_session smallint;
  derived_batch smallint;
  resolved_batch uuid;
begin
  p := classmate.create_or_initialize_profile();
  if p.role <> 'student' then raise exception 'Student onboarding only' using errcode = '42501'; end if;
  if p.department_id is not null and p.verification_status = 'active' then return p; end if;

  select * into d from classmate.departments where id = selected_department and is_active;
  if d.id is null then raise exception 'Department unavailable'; end if;

  if d.email_prefix is not null then
    matched := regexp_match(p.email, '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if matched is null or matched[1] <> d.email_prefix then
      raise exception 'Institutional email does not match department';
    end if;
    derived_session := matched[2]::smallint;
    derived_batch := derived_session - d.session_offset;
    if derived_batch < 0 then raise exception 'Invalid department session offset'; end if;
    insert into classmate.batches(department_id, batch_number, academic_session)
    values (d.id, derived_batch, derived_session)
    on conflict (department_id, batch_number) do nothing;
    select id into resolved_batch from classmate.batches
      where department_id = d.id and batch_number = derived_batch
        and academic_session = derived_session and is_active;
    if resolved_batch is null then raise exception 'Batch/session conflict'; end if;
    update classmate.profiles set department_id = d.id, batch_id = resolved_batch,
      student_id = upper(matched[1] || matched[2] || matched[3]),
      academic_session = derived_session, requested_batch_number = null,
      profile_source = 'auto', verification_status = 'active', rejection_reason = null,
      updated_at = now() where id = p.id returning * into p;
  else
    if entered_student_id is null or trim(entered_student_id) !~ '^[A-Za-z0-9-]{4,30}$'
      or entered_batch_number is null or entered_batch_number not between 0 and 99
      or entered_session is null or entered_session not between 0 and 99 then
      raise exception 'Student ID, batch, and session are required';
    end if;
    update classmate.profiles set department_id = d.id, batch_id = null,
      student_id = upper(trim(entered_student_id)), academic_session = entered_session,
      requested_batch_number = entered_batch_number, profile_source = 'manual',
      verification_status = 'pending', rejection_reason = null, updated_at = now()
    where id = p.id returning * into p;
  end if;
  return p;
end;
$function$;

-- 3. Purge RU data (batch 0ca469cb-f6af-4ea2-bcbd-96356739f739, dept 9990129f-7f7e-42ea-a325-ff192fd068ee, inst a0000000-0000-0000-0000-000000000002)
DELETE FROM classmate.notification_outbox WHERE batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM classmate.notices WHERE batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM classmate.routine_slots WHERE semester_course_id IN (
  SELECT sc.id FROM classmate.semester_courses sc
  JOIN classmate.semesters s ON s.id = sc.semester_id
  WHERE s.batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739'
);
DELETE FROM classmate.semester_courses WHERE semester_id IN (
  SELECT id FROM classmate.semesters WHERE batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739'
);
DELETE FROM classmate.semesters WHERE batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM classmate.courses WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee';
DELETE FROM classmate.teacher_allowlist WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee';
DELETE FROM classmate.cr_allowlist WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee' OR batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM classmate.teacher_directory WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee';
DELETE FROM classmate.batch_overrides WHERE effective_batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739' OR added_by IN (
  SELECT id FROM classmate.profiles WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee' OR batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739'
);
DELETE FROM classmate.device_tokens WHERE profile_id IN (
  SELECT id FROM classmate.profiles WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee' OR batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739'
);
DELETE FROM classmate.profiles WHERE department_id = '9990129f-7f7e-42ea-a325-ff192fd068ee' OR batch_id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM auth.users WHERE email LIKE '%@ru.ac.bd';
DELETE FROM classmate.batches WHERE id = '0ca469cb-f6af-4ea2-bcbd-96356739f739';
DELETE FROM classmate.departments WHERE id = '9990129f-7f7e-42ea-a325-ff192fd068ee';
DELETE FROM classmate.institutions WHERE id = 'a0000000-0000-0000-0000-000000000002';

DROP TABLE IF EXISTS classmate.preseeded_student_roster;

NOTIFY pgrst, 'reload schema';

COMMIT;
