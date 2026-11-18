-- Migration: 202611060001_rajshahi_university_fisheries_batch25.sql
-- Enables University of Rajshahi (RU), Faculty of Fisheries, Batch 25 (Session 2024-25)
-- Supports s[10-digit-id]@ru.ac.bd, pre-seeded student roster, and auto interim CR assignment.

-- 1. Ensure University of Rajshahi exists
insert into public.universities (code, name, email_domain, is_active)
values ('RU', 'University of Rajshahi', 'ru.ac.bd', true)
on conflict (code) do update set email_domain = 'ru.ac.bd', is_active = true;

-- 2. Ensure Faculty of Fisheries department exists in classmate
insert into classmate.departments (name, code, email_prefix, session_offset, is_active)
values ('Faculty of Fisheries', 'fisheries', null, null, true)
on conflict (code) do update set is_active = true, name = 'Faculty of Fisheries';

-- 3. Ensure Batch 25 (Academic Session 25 = 2024-25) exists for Fisheries
insert into classmate.batches (department_id, batch_number, academic_session, is_active)
select d.id, 25, 25, true
from classmate.departments d
where d.code = 'fisheries'
on conflict (department_id, batch_number) do update set academic_session = 25, is_active = true;

-- 4. Ensure Semester 1 exists and is active for Batch 25
insert into classmate.semesters (batch_id, semester_number, status, published_at)
select b.id, 1, 'active', now()
from classmate.batches b
join classmate.departments d on d.id = b.department_id
where d.code = 'fisheries' and b.batch_number = 25
on conflict (batch_id, semester_number) do nothing;

-- 5. Expand profiles email constraint to allow @ru.ac.bd
alter table classmate.profiles drop constraint if exists profiles_email_check;
alter table classmate.profiles add constraint profiles_email_check check (
  email = lower(email) and email ~ '^[^[:space:]@]+@[^[:space:]@]+$' and (
    email like '%@mbstu.ac.bd'
    or email like '%@ru.ac.bd'
    or (role = 'teacher' and email ~ '^[^[:space:]@]+@gmail[.]com$')
  )
);

-- 6. Pre-seeded roster table for verified instant onboarding
create table if not exists classmate.preseeded_student_roster (
  student_id text primary key,
  full_name text not null,
  email text not null unique,
  department_code text not null,
  batch_number smallint not null,
  academic_session smallint not null,
  is_readd boolean not null default false,
  created_at timestamptz not null default now()
);

grant select on classmate.preseeded_student_roster to authenticated, anon;

-- Populate Batch 25 Fisheries students
insert into classmate.preseeded_student_roster (student_id, full_name, email, department_code, batch_number, academic_session, is_readd)
values
  ('2512367101', 'LABONY AKTER FATEMA', 's2512367101@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510967102', 'MD. FERDOUS HASAN JISAN', 's2510967102@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512667103', 'SUMAYA AKTER SHORMI', 's2512667103@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510467104', 'MD. SHAHIDUL ISLAM', 's2510467104@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512467105', 'MST. MONAKKA JANNAT MOUFE', 's2512467105@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510567106', 'RUDRA DEV NATH AKASH', 's2510567106@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510567107', 'ANUPOM PRANTO TUDU', 's2510567107@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510867108', 'MD. NAYEEM', 's2510867108@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512667109', 'KUMARI PRIYANKA RANI KUNDU', 's2512667109@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512167112', 'MOST. NAFISA KHATUN SIMIYA', 's2512167112@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510267114', 'MD. RISHAD BABU', 's2510267114@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512167116', 'RUMANA RASHID RIYA', 's2512167116@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510467117', 'SOUROV KUMAR BISWAS', 's2510467117@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510467118', 'TASHNIM JAHAN SADIA', 's2510467118@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512467120', 'AFIA HUMAYRA', 's2512467120@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2511067121', 'MD. ASHIKUR RAHMAN RATUL', 's2511067121@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512367123', 'TAMANNA AKTAR RISHA', 's2512367123@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510767124', 'MD. HANIF', 's2510767124@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512667125', 'MST. SHAHANAJ PARVIN SHAMMI', 's2512667125@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510767126', 'PRINCE KHAN', 's2510767126@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512167128', 'MST. LABONI AKTAR', 's2512167128@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512467129', 'MST. SUMAYA PARVIN BOBY', 's2512467129@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512467130', 'SAMIHA FARZANA', 's2512467130@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510567132', 'FAYSAL AHAMED', 's2510567132@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510467136', 'MOHAMMED SHAMIULLAH', 's2510467136@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510467137', 'ARAFAT RAHMAN', 's2510467137@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2510567138', 'RIYADH HOSSAIN', 's2510567138@ru.ac.bd', 'fisheries', 25, 25, false),
  ('2512167141', 'MOST. NISAT ANZUM ASHA', 's2512167141@ru.ac.bd', 'fisheries', 25, 25, false)
on conflict (student_id) do update set
  full_name = excluded.full_name,
  email = excluded.email,
  department_code = excluded.department_code,
  batch_number = excluded.batch_number,
  academic_session = excluded.academic_session,
  is_readd = excluded.is_readd;

-- 7. Update classmate.create_or_initialize_profile() to handle RU logins, pre-seeded match, and interim CR
create or replace function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare 
  u auth.users%rowtype; 
  result classmate.profiles%rowtype; 
  dept uuid;
  v_batch uuid;
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

  -- Verify authorized domain: @mbstu.ac.bd, @ru.ac.bd, or approved teacher Gmail
  if lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$'
     and lower(u.email) !~ '^s[0-9]{8,10}@ru[.]ac[.]bd$'
     and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your university account (@mbstu.ac.bd, @ru.ac.bd) or an admin-approved teacher Gmail' using errcode='28000';
  end if;

  -- Check if app owner (admin)
  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    matched := regexp_match(lower(u.email), '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if matched is not null then
      select d.id into dept from classmate.departments d where d.email_prefix = matched[1] and d.is_active;
      if dept is not null then
        select b.id into v_batch from classmate.batches b 
        where b.department_id = dept and b.academic_session = matched[2]::smallint and b.is_active;
      end if;
      parsed_student_id := upper(matched[1] || matched[2] || matched[3]);
      parsed_session := matched[2]::smallint;
    end if;

    insert into classmate.profiles(id, email, full_name, role, department_id, batch_id, student_id, academic_session, verification_status)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', dept, v_batch, parsed_student_id, parsed_session, 'active')
    on conflict (id) do update set 
      role = 'admin', 
      verification_status = 'active',
      department_id = coalesce(classmate.profiles.department_id, excluded.department_id),
      batch_id = coalesce(classmate.profiles.batch_id, excluded.batch_id),
      student_id = coalesce(classmate.profiles.student_id, excluded.student_id),
      academic_session = coalesce(classmate.profiles.academic_session, excluded.academic_session),
      updated_at = now();

  else
    -- Check teacher allowlist
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active')
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', updated_at = now();
    else
      -- Check CR allowlist
      select * into v_cr from classmate.cr_allowlist where email = lower(u.email) and active limit 1;
      if v_cr.email is not null then
        insert into classmate.profiles(id, email, full_name, role, department_id, batch_id, student_id, academic_session, verification_status, is_cr, cr_batch_id)
        values (
          u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
          'student', v_cr.department_id, v_cr.batch_id, v_cr.student_id, v_cr.academic_session,
          'active', true, v_cr.batch_id
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
          updated_at = now();
      else
        -- Check Rajshahi University student email pattern: s[10-digit ID]@ru.ac.bd
        ru_matched := regexp_match(lower(u.email), '^s([0-9]{8,10})@ru[.]ac[.]bd$');
        if ru_matched is not null then
          parsed_student_id := ru_matched[1];
          select * into v_roster from classmate.preseeded_student_roster
            where student_id = parsed_student_id or email = lower(u.email) limit 1;

          select d.id into dept from classmate.departments d
            where d.code = coalesce(v_roster.department_code, 'fisheries') and d.is_active limit 1;

          select b.id into v_batch from classmate.batches b
            where b.department_id = dept
              and b.batch_number = coalesce(v_roster.batch_number, 25)
              and b.is_active limit 1;

          parsed_session := coalesce(v_roster.academic_session, 25);
          effective_name := coalesce(v_roster.full_name, nullif(trim(u.raw_user_meta_data->>'full_name'), ''), 'Fisheries Student');

          -- Determine if this batch currently has an active CR
          select exists(
            select 1 from classmate.profiles
            where batch_id = v_batch and is_cr and verification_status = 'active'
          ) into has_active_cr;

          -- First student of the batch becomes interim CR!
          insert into classmate.profiles(
            id, email, full_name, role, department_id, batch_id,
            student_id, academic_session, profile_source, verification_status,
            is_cr, cr_batch_id
          ) values (
            u.id, lower(u.email), effective_name, 'student', dept, v_batch,
            parsed_student_id, parsed_session, 'auto', 'active',
            not has_active_cr, case when not has_active_cr then v_batch else null end
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
            updated_at = now();

        else
          -- Standard MBSTU student signup
          insert into classmate.profiles(id, email, full_name, role, verification_status)
          values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
            'student', 'pending')
          on conflict (id) do update set role = 'student',
            verification_status = case when classmate.profiles.role <> 'student'
              then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
            department_id = case when classmate.profiles.role <> 'student'
              then null else classmate.profiles.department_id end,
            updated_at = now();
        end if;
      end if;
    end if;
  end if;

  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

-- 8. Update complete_student_onboarding() to support RU Fisheries auto-resolution
create or replace function classmate.complete_student_onboarding(
  selected_department uuid, entered_student_id text default null,
  entered_batch_number smallint default null, entered_session smallint default null
) returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare 
  p classmate.profiles%rowtype; 
  d classmate.departments%rowtype;
  matched text[]; 
  ru_matched text[];
  derived_session smallint; 
  derived_batch smallint; 
  resolved_batch uuid;
  has_active_cr boolean;
  v_roster classmate.preseeded_student_roster%rowtype;
begin
  p := classmate.create_or_initialize_profile();
  if p.role <> 'student' then raise exception 'Student onboarding only' using errcode = '42501'; end if;
  if p.department_id is not null and p.verification_status = 'active' then return p; end if;

  select * into d from classmate.departments where id = selected_department and is_active;
  if d.id is null then raise exception 'Department unavailable'; end if;

  -- Case A: RU Fisheries student email
  ru_matched := regexp_match(p.email, '^s([0-9]{8,10})@ru[.]ac[.]bd$');
  if ru_matched is not null or d.code = 'fisheries' then
    select * into v_roster from classmate.preseeded_student_roster
      where email = p.email or student_id = ru_matched[1] limit 1;

    derived_batch := coalesce(v_roster.batch_number, entered_batch_number, 25);
    derived_session := coalesce(v_roster.academic_session, entered_session, 25);

    select id into resolved_batch from classmate.batches
      where department_id = d.id and batch_number = derived_batch and is_active limit 1;

    select exists(
      select 1 from classmate.profiles
      where batch_id = resolved_batch and is_cr and verification_status = 'active'
    ) into has_active_cr;

    update classmate.profiles set
      department_id = d.id,
      batch_id = resolved_batch,
      student_id = coalesce(ru_matched[1], v_roster.student_id, entered_student_id),
      academic_session = derived_session,
      requested_batch_number = null,
      profile_source = 'auto',
      verification_status = 'active',
      rejection_reason = null,
      is_cr = case when is_cr then true when not has_active_cr then true else false end,
      cr_batch_id = case when is_cr then cr_batch_id when not has_active_cr then resolved_batch else null end,
      updated_at = now()
    where id = p.id returning * into p;

    return p;
  end if;

  -- Case B: MBSTU departmental email with prefix
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
$$;
