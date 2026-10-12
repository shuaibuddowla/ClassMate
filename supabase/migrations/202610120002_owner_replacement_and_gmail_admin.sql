begin;

-- ============================================================================
-- 1. Support Gmail App Owners in Constraints
-- ============================================================================
alter table classmate.app_owners 
  drop constraint if exists app_owners_email_check;

alter table classmate.app_owners 
  add constraint app_owners_email_check 
  check (email = lower(trim(email)) and email ~ '^[^[:space:]@]+@(mbstu[.]ac[.]bd|gmail[.]com)$');

alter table classmate.profiles 
  drop constraint if exists profiles_email_check;

alter table classmate.profiles 
  add constraint profiles_email_check 
  check (
    email = lower(email) 
    and email ~ '^[^[:space:]@]+@[^[:space:]@]+$' 
    and (
      email like '%@mbstu.ac.bd' 
      or (role in ('teacher', 'admin') and email ~ '^[^[:space:]@]+@gmail[.]com$')
    )
  );

-- ============================================================================
-- 2. Update Profile Initialization RPC to Permit App Owners with Gmail
-- ============================================================================
create or replace function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare 
  u auth.users%rowtype; 
  result classmate.profiles%rowtype; 
  dept uuid;
begin
  if auth.uid() is null then 
    raise exception 'Authentication required' using errcode = '28000'; 
  end if;

  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;

  if lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$' 
     and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active))
     and not exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    raise exception 'Use your university account or an admin-approved teacher Gmail' using errcode='28000';
  end if;

  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    insert into classmate.profiles(id, email, full_name, role, verification_status)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', 'active')
    on conflict (id) do update set role = 'admin', verification_status = 'active',
      department_id = null, batch_id = null, student_id = null, is_cr = false,
      cr_batch_id = null, cr_valid_until = null, updated_at = now()
    returning * into result;
    return result;
  else
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active')
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', updated_at = now()
      returning * into result;
      return result;
    else
      insert into classmate.profiles(id, email, full_name, role, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'student', 'pending')
      on conflict (id) do update set role = 'student',
        verification_status = case when classmate.profiles.role <> 'student'
          then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
        department_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.department_id end,
        batch_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.batch_id end,
        student_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.student_id end,
        academic_session = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.academic_session end,
        is_cr = case when classmate.profiles.role <> 'student'
          then false else classmate.profiles.is_cr end,
        cr_batch_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.cr_batch_id end,
        cr_valid_until = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.cr_valid_until end,
        updated_at = now()
      returning * into result;
      return result;
    end if;
  end if;
end;
$$;

-- ============================================================================
-- 3. Resilient Developer Profile RPC
-- ============================================================================
create or replace function classmate.developer_profile() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'full_name', coalesce(p.full_name, 'Mohammad Shuaib-Ud-Dowla'),
    'student_id', coalesce(p.student_id, 'CE25045'),
    'department', coalesce(d.name, 'Computer Science and Engineering'),
    'department_code', coalesce(d.code, 'cse'),
    'batch_number', coalesce(b.batch_number, 22),
    'academic_session', coalesce(b.academic_session, 25),
    'avatar_url', case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') else null end)
  from classmate.app_owners o
  left join classmate.profiles p on p.email = o.email
  left join auth.users u on lower(u.email) = o.email
  left join classmate.departments d on d.id = p.department_id or d.code = 'cse'
  left join classmate.batches b on b.id = p.batch_id or (b.department_id = d.id and b.batch_number = 22)
  where o.active
  order by (p.full_name is not null) desc, p.id limit 1;
$$;

-- ============================================================================
-- 4. Replace Owner in app_owners & Migrate ce25045 to CSE22 Student
-- ============================================================================
-- Remove ce25045 from app_owners
delete from classmate.app_owners where email = 'ce25045@mbstu.ac.bd';

-- Add new owner shuaibuddowla.personal@gmail.com
insert into classmate.app_owners (email, active)
values ('shuaibuddowla.personal@gmail.com', true)
on conflict (email) do update set active = true;

-- Convert ce25045@mbstu.ac.bd into CSE22 Student
do $$
declare
  v_cse_dept uuid;
  v_cse22_batch uuid;
begin
  select id into v_cse_dept from classmate.departments where code = 'cse' limit 1;
  select id into v_cse22_batch from classmate.batches where department_id = v_cse_dept and batch_number = 22 limit 1;

  update classmate.profiles
  set
    role = 'student',
    department_id = v_cse_dept,
    batch_id = v_cse22_batch,
    student_id = 'CE25045',
    academic_session = 25,
    verification_status = 'active',
    is_cr = false,
    cr_batch_id = null,
    cr_valid_until = null,
    updated_at = now()
  where email = 'ce25045@mbstu.ac.bd';

  -- If shuaibuddowla.personal@gmail.com already has a profile record, promote to admin
  update classmate.profiles
  set
    role = 'admin',
    verification_status = 'active',
    department_id = null,
    batch_id = null,
    student_id = null,
    is_cr = false,
    cr_batch_id = null,
    cr_valid_until = null,
    updated_at = now()
  where email = 'shuaibuddowla.personal@gmail.com';
end $$;

commit;
notify pgrst, 'reload schema';
