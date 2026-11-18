-- 202611060006_fix_v_roster_id_field.sql
-- Fix: classmate.preseeded_student_roster uses student_id as identifier (has no column 'id')

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
  ru_matched text[];
  parsed_student_id text;
  parsed_session smallint;
  v_cr classmate.cr_allowlist%rowtype;
  v_roster classmate.preseeded_student_roster%rowtype;
  has_active_cr boolean;
  effective_name text;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;

  v_domain := lower(split_part(u.email, '@', 2));

  -- Dynamically resolve institution by email domain
  select * into v_inst from classmate.institutions
  where (email_domain = v_domain or v_domain like '%.' || email_domain) and is_active
  limit 1;

  -- Verify authorized domain: any registered institution or approved teacher Gmail
  if v_inst.id is null and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your recognized university Google account or an admin-approved teacher Gmail' using errcode='28000';
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
        -- Preseeded student roster lookup (supports any university's preseeded students)
        select * into v_roster from classmate.preseeded_student_roster
          where email = lower(u.email)
             or (student_id is not null and lower(u.email) like '%' || student_id || '%')
          limit 1;

        if v_roster.student_id is not null then
          select d.id into dept from classmate.departments d
            where d.code = v_roster.department_code and (v_inst.id is null or d.institution_id = v_inst.id) and d.is_active limit 1;

          select b.id into v_batch from classmate.batches b
            where b.department_id = dept
              and b.batch_number = coalesce(v_roster.batch_number, 25)
              and b.is_active limit 1;

          parsed_session := coalesce(v_roster.academic_session, 25);
          effective_name := coalesce(v_roster.full_name, nullif(trim(u.raw_user_meta_data->>'full_name'), ''), 'Student');

          -- Determine if this batch currently has an active CR
          select exists(
            select 1 from classmate.profiles
            where batch_id = v_batch and is_cr and verification_status = 'active'
          ) into has_active_cr;

          insert into classmate.profiles(
            id, email, full_name, role, department_id, batch_id,
            student_id, academic_session, profile_source, verification_status,
            is_cr, cr_batch_id, institution_id
          ) values (
            u.id, lower(u.email), effective_name, 'student', dept, v_batch,
            v_roster.student_id, parsed_session, 'auto', 'active',
            not has_active_cr, case when not has_active_cr then v_batch else null end,
            v_inst.id
          )
          on conflict (id) do update set
            role = 'student',
            full_name = case when classmate.profiles.full_name = '' then excluded.full_name else classmate.profiles.full_name end,
            department_id = excluded.department_id,
            batch_id = excluded.batch_id,
            student_id = excluded.student_id,
            academic_session = excluded.academic_session,
            verification_status = 'active',
            profile_source = 'auto',
            is_cr = case when classmate.profiles.is_cr then true when not has_active_cr then true else false end,
            cr_batch_id = case when classmate.profiles.is_cr then classmate.profiles.cr_batch_id when not has_active_cr then excluded.batch_id else null end,
            institution_id = excluded.institution_id,
            updated_at = now();

        else
          -- Standard student onboarding flow for any university
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
