-- 202611070006_email_batch_overrides_and_notification_fix.sql
-- Enables university email support in batch overrides (for dropouts / re-add students across all universities)
-- and restores app_update broadcast delivery in notification_recipients.

begin;

-- ============================================================================
-- 1. Extend classmate.batch_overrides to support university email
-- ============================================================================
alter table classmate.batch_overrides add column if not exists email text;

-- Allow student_id to be nullable if email is provided
alter table classmate.batch_overrides drop constraint if exists batch_overrides_student_id_check;
alter table classmate.batch_overrides alter column student_id drop not null;

-- Add check: either student_id or email must be present and valid
alter table classmate.batch_overrides drop constraint if exists batch_overrides_identifier_check;
alter table classmate.batch_overrides add constraint batch_overrides_identifier_check
  check (
    (student_id is not null and length(trim(student_id)) between 4 and 30)
    or
    (email is not null and email = lower(trim(email)) and email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')
  );

-- Unique index on lower(trim(email)) where email is not null
create unique index if not exists batch_overrides_email_unique
  on classmate.batch_overrides (lower(trim(email)))
  where email is not null;

-- Populate existing emails where student_id matches a registered profile
update classmate.batch_overrides bo
set email = p.email
from classmate.profiles p
where bo.email is null
  and bo.student_id is not null
  and upper(trim(p.student_id)) = upper(trim(bo.student_id))
  and p.email is not null;

-- Populate known RU re-add student email if matching 2410467135
update classmate.batch_overrides
set email = 's2410467135@ru.ac.bd'
where student_id = '2410467135' and email is null;

-- ============================================================================
-- 2. Update set_batch_override to accept either University Email or Student ID
-- ============================================================================
create or replace function classmate.set_batch_override(
  target_student_id text,
  target_batch uuid,
  target_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  raw_input text := trim(coalesce(target_student_id, ''));
  v_email text := null;
  norm_id text := null;
  batch_row classmate.batches%rowtype;
  override_row classmate.batch_overrides%rowtype;
  profile_updated boolean := false;
  matched_profile classmate.profiles%rowtype;
  ru_matched text[];
  mbstu_matched text[];
begin
  if not classmate.can_manage_batch(target_batch) then
    raise exception 'Unauthorized to manage overrides for this batch' using errcode = '42501';
  end if;

  select * into batch_row from classmate.batches where id = target_batch and is_active;
  if batch_row.id is null then
    raise exception 'Target batch not found or inactive' using errcode = '22023';
  end if;

  -- Determine if raw_input is an institutional email or student ID
  if raw_input like '%@%' then
    v_email := lower(raw_input);
    if v_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$' then
      raise exception 'Enter a valid university email address' using errcode = '22023';
    end if;

    -- Infer student_id from email if possible
    ru_matched := regexp_match(v_email, '^(?:s)?([0-9]{8,10})@ru[.]ac[.]bd$');
    mbstu_matched := regexp_match(v_email, '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if ru_matched is not null then
      norm_id := ru_matched[1];
    elsif mbstu_matched is not null then
      norm_id := upper(mbstu_matched[1] || mbstu_matched[2] || mbstu_matched[3]);
    end if;

    -- Look up existing profile by email
    select * into matched_profile from classmate.profiles
    where lower(trim(email)) = v_email and role = 'student' for update;

    if matched_profile.id is not null and matched_profile.student_id is not null then
      norm_id := upper(trim(matched_profile.student_id));
    end if;

  else
    norm_id := upper(raw_input);
    if length(norm_id) not between 4 and 30 or norm_id !~ '^[A-Za-z0-9-]{4,30}$' then
      raise exception 'Enter a valid student ID (e.g. CE23045 or 2410467135) or university email' using errcode = '22023';
    end if;

    -- Look up existing profile by student_id
    select * into matched_profile from classmate.profiles
    where upper(trim(student_id)) = norm_id and role = 'student' for update;

    if matched_profile.id is not null and matched_profile.email is not null then
      v_email := lower(trim(matched_profile.email));
    end if;
  end if;

  -- Upsert override row: match by email or student_id
  if v_email is not null and norm_id is not null then
    insert into classmate.batch_overrides (student_id, email, effective_batch_id, reason, added_by, created_at)
    values (norm_id, v_email, batch_row.id, nullif(trim(target_reason), ''), auth.uid(), now())
    on conflict (student_id) do update set
      email = coalesce(excluded.email, classmate.batch_overrides.email),
      effective_batch_id = excluded.effective_batch_id,
      reason = excluded.reason,
      added_by = excluded.added_by,
      created_at = now()
    returning * into override_row;
  elsif v_email is not null then
    -- Check if row with email exists
    select * into override_row from classmate.batch_overrides where lower(trim(email)) = v_email;
    if override_row.id is not null then
      update classmate.batch_overrides
      set effective_batch_id = batch_row.id,
          reason = nullif(trim(target_reason), ''),
          added_by = auth.uid(),
          created_at = now()
      where id = override_row.id
      returning * into override_row;
    else
      insert into classmate.batch_overrides (email, effective_batch_id, reason, added_by, created_at)
      values (v_email, batch_row.id, nullif(trim(target_reason), ''), auth.uid(), now())
      returning * into override_row;
    end if;
  else
    insert into classmate.batch_overrides (student_id, effective_batch_id, reason, added_by, created_at)
    values (norm_id, batch_row.id, nullif(trim(target_reason), ''), auth.uid(), now())
    on conflict (student_id) do update set
      effective_batch_id = excluded.effective_batch_id,
      reason = excluded.reason,
      added_by = excluded.added_by,
      created_at = now()
    returning * into override_row;
  end if;

  -- If student already has a registered profile, immediately move them to the target batch
  if matched_profile.id is not null then
    update classmate.profiles
    set batch_id = batch_row.id,
        department_id = batch_row.department_id,
        academic_session = batch_row.academic_session,
        verification_status = 'active',
        rejection_reason = null,
        is_cr = case when is_cr and cr_batch_id is distinct from batch_row.id then false else is_cr end,
        cr_batch_id = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_batch_id end,
        cr_valid_until = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_valid_until end,
        updated_at = now()
    where id = matched_profile.id;
    profile_updated := true;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'set_batch_override',
    batch_row.id,
    jsonb_build_object(
      'student_id', norm_id,
      'email', v_email,
      'effective_batch_id', batch_row.id,
      'reason', override_row.reason,
      'profile_updated', profile_updated,
      'profile_id', matched_profile.id
    )
  );

  return jsonb_build_object(
    'id', override_row.id,
    'student_id', override_row.student_id,
    'email', override_row.email,
    'effective_batch_id', override_row.effective_batch_id,
    'reason', override_row.reason,
    'created_at', override_row.created_at,
    'profile_updated', profile_updated
  );
end;
$$;

-- ============================================================================
-- 3. Update delete_batch_override to support email or student ID or override ID
-- ============================================================================
create or replace function classmate.delete_batch_override(
  target_student_id text default null,
  target_override_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  raw_input text := trim(coalesce(target_student_id, ''));
  existing_row classmate.batch_overrides%rowtype;
  deleted_row classmate.batch_overrides%rowtype;
begin
  if target_override_id is not null then
    select * into existing_row from classmate.batch_overrides where id = target_override_id;
  elsif raw_input like '%@%' then
    select * into existing_row from classmate.batch_overrides where lower(trim(email)) = lower(raw_input);
  else
    select * into existing_row from classmate.batch_overrides where upper(trim(student_id)) = upper(raw_input);
  end if;

  if existing_row.id is null then
    raise exception 'Batch override not found' using errcode = 'P0002';
  end if;

  if not classmate.can_manage_batch(existing_row.effective_batch_id) then
    raise exception 'Unauthorized to delete override for this batch' using errcode = '42501';
  end if;

  delete from classmate.batch_overrides
  where id = existing_row.id
  returning * into deleted_row;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'delete_batch_override',
    deleted_row.effective_batch_id,
    jsonb_build_object(
      'override_id', deleted_row.id,
      'student_id', deleted_row.student_id,
      'email', deleted_row.email,
      'effective_batch_id', deleted_row.effective_batch_id
    )
  );

  return jsonb_build_object(
    'id', deleted_row.id,
    'student_id', deleted_row.student_id,
    'email', deleted_row.email,
    'effective_batch_id', deleted_row.effective_batch_id,
    'deleted', true
  );
end;
$$;

-- ============================================================================
-- 4. Update owner_batch_overrides to return email
-- ============================================================================
drop function if exists classmate.owner_batch_overrides();
create or replace function classmate.owner_batch_overrides()
returns table (
  id uuid,
  student_id text,
  email text,
  effective_batch_id uuid,
  reason text,
  added_by uuid,
  created_at timestamptz,
  batch_name text,
  department_name text
)
language sql
security definer
set search_path to ''
as $$
  select
    o.id,
    o.student_id,
    o.email,
    o.effective_batch_id,
    o.reason,
    o.added_by,
    o.created_at,
    'Batch ' || b.batch_number as batch_name,
    d.name as department_name
  from classmate.batch_overrides o
  join classmate.batches b on b.id = o.effective_batch_id
  join classmate.departments d on d.id = b.department_id
  where classmate.can_manage_batch(o.effective_batch_id)
  order by o.created_at desc;
$$;

-- ============================================================================
-- 5. Update is_readd_student to check email as well
-- ============================================================================
create or replace function classmate.is_readd_student(
  p_student_id text,
  p_batch_id uuid,
  p_email text default null
)
returns boolean
language plpgsql
security definer
set search_path to ''
as $$
declare
  v_is_override boolean;
  v_batch_session integer;
  v_stu_session integer;
begin
  -- 1. Check explicit batch_overrides table by email or student_id
  select exists(
    select 1 from classmate.batch_overrides bo
    where (p_email is not null and lower(trim(bo.email)) = lower(trim(p_email)))
       or (p_student_id is not null and trim(p_student_id) <> '' and upper(trim(bo.student_id)) = upper(trim(p_student_id)))
  ) into v_is_override;

  if v_is_override then
    return true;
  end if;

  -- 2. Check student ID prefix session vs batch session (e.g. CE23045 in Batch 21)
  if p_student_id is not null and p_batch_id is not null then
    select b.academic_session into v_batch_session
    from classmate.batches b where b.id = p_batch_id;

    if p_student_id ~ '^[A-Za-z]+([0-9]{2})[0-9]+' then
      v_stu_session := substring(p_student_id from '^[A-Za-z]+([0-9]{2})')::integer;
      if v_batch_session is not null and v_stu_session < v_batch_session then
        return true;
      end if;
    end if;
  end if;

  return false;
end;
$$;

-- ============================================================================
-- 6. Update create_or_initialize_profile to check batch_overrides on student signup!
-- ============================================================================
create or replace function classmate.create_or_initialize_profile()
returns classmate.profiles
language plpgsql
security definer
set search_path to ''
as $$
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
  v_override classmate.batch_overrides%rowtype;
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
        -- Pre-check: Does an administrative BATCH OVERRIDE exist for this student (re-admitted / dropout)?
        ru_matched := regexp_match(lower(u.email), '^(?:s)?([0-9]{8,10})@ru[.]ac[.]bd$');
        matched := regexp_match(lower(u.email), '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
        if ru_matched is not null then
          parsed_student_id := ru_matched[1];
        elsif matched is not null then
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
          -- Preseeded student roster lookup
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
            -- Standard student onboarding flow
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
  end if;

  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

-- ============================================================================
-- 7. Fix notification_recipients to deliver app_update to all active users
-- ============================================================================
create or replace function classmate.notification_recipients(target_event uuid)
returns table (
  profile_id uuid,
  fcm_token text
)
language plpgsql
security definer
set search_path to ''
as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return; end if;

  -- Case A: App update announcement -> Deliver to all active registered users across all universities!
  if e.kind = 'app_update' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.verification_status = 'active';
    return;
  end if;

  -- Case B: Targeted personal event (student ban, ban lifted, direct alert)
  if e.recipient_profile_id is not null then
    if e.kind in ('student_ban', 'student_ban_lifted') then
      return query
        select distinct p.id, dt.fcm_token
        from classmate.profiles p
        join classmate.device_tokens dt on dt.profile_id = p.id
        where p.id = e.recipient_profile_id and p.verification_status = 'active';
      return;
    end if;

    -- For any other targeted personal event, do NOT deliver if the student is currently banned
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        );
      return;
  end if;

  -- Case C: Batch / academic event broadcast (notices, routine changes, class cancellations, resources)
  -- Strictly ISOLATED to the specific batch & department!
  -- Strictly EXCLUDES any student who currently has an active ban!
  return query
    select distinct p.id, dt.fcm_token
    from classmate.profiles p
    join classmate.device_tokens dt on dt.profile_id = p.id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where (
      (
        p.role = 'student'
        and p.batch_id = b.id
        and p.department_id = b.department_id
        and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        )
      )
      or (p.role = 'teacher' and p.verification_status = 'active' and exists (
        select 1 from classmate.teacher_course_assignments a
        join classmate.semester_courses sc on sc.id = a.semester_course_id
        join classmate.semesters s on s.id = sc.semester_id
        join classmate.teacher_allowlist t on t.email = p.email and t.department_id = b.department_id and t.active
        where a.teacher_id = p.id and a.active and s.batch_id = b.id and s.status = 'active'
          and (e.semester_course_id is null or sc.id = e.semester_course_id)
      ))
    );
end;
$$;

commit;
