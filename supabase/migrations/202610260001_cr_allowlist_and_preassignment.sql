-- Migration: Create CR Allowlist for Pre-Assigning Batch CRs before First Sign-In
-- ==============================================================================

-- 1. Create classmate.cr_allowlist table
create table if not exists classmate.cr_allowlist (
  email text primary key check (email = lower(email) and email like '%@mbstu.ac.bd'),
  department_id uuid not null references classmate.departments(id),
  batch_id uuid not null,
  student_id text not null,
  academic_session smallint not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (batch_id, department_id) references classmate.batches(id, department_id)
);

alter table classmate.cr_allowlist enable row level security;

create policy cr_allowlist_read on classmate.cr_allowlist for select to authenticated
  using (classmate.is_owner() or email = lower(auth.jwt()->>'email'));

-- 2. Create helper RPC for managing CR allowlist
create or replace function classmate.set_cr_allowlist(
  target_email text,
  target_batch uuid,
  target_active boolean default true
) returns classmate.cr_allowlist
language plpgsql security definer set search_path = '' as $$
declare
  b classmate.batches%rowtype;
  d classmate.departments%rowtype;
  matched text[];
  v_student_id text;
  row_result classmate.cr_allowlist%rowtype;
begin
  perform classmate.require_owner();
  select * into b from classmate.batches where id = target_batch and is_active;
  if b.id is null then raise exception 'Batch not found or inactive'; end if;
  select * into d from classmate.departments where id = b.department_id and is_active;
  if d.id is null then raise exception 'Department not found or inactive'; end if;

  matched := regexp_match(lower(trim(target_email)), '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
  if matched is null then
    raise exception 'Invalid student email format (expected e.g. bg25004@mbstu.ac.bd)';
  end if;

  v_student_id := upper(matched[1] || matched[2] || matched[3]);

  insert into classmate.cr_allowlist(email, department_id, batch_id, student_id, academic_session, active, updated_at)
  values (lower(trim(target_email)), d.id, b.id, v_student_id, b.academic_session, target_active, now())
  on conflict (email) do update set
    department_id = excluded.department_id,
    batch_id = excluded.batch_id,
    student_id = excluded.student_id,
    academic_session = excluded.academic_session,
    active = excluded.active,
    updated_at = now()
  returning * into row_result;

  -- If user already exists in profiles, update them immediately to active CR
  update classmate.profiles set
    role = 'student',
    department_id = d.id,
    batch_id = b.id,
    student_id = v_student_id,
    academic_session = b.academic_session,
    verification_status = 'active',
    is_cr = target_active,
    cr_batch_id = case when target_active then b.id else null end,
    updated_at = now()
  where email = lower(trim(target_email));

  return row_result;
end;
$$;

revoke all on function classmate.set_cr_allowlist(text, uuid, boolean) from public, anon;
grant execute on function classmate.set_cr_allowlist(text, uuid, boolean) to authenticated;

-- 3. Update create_or_initialize_profile to check CR allowlist
create or replace function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare 
  u auth.users%rowtype; 
  result classmate.profiles%rowtype; 
  dept uuid;
  v_batch uuid;
  matched text[];
  parsed_student_id text;
  parsed_session smallint;
  v_cr classmate.cr_allowlist%rowtype;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;
  if lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$' and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your university account or an admin-approved teacher Gmail' using errcode='28000';
  end if;

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
  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

-- 4. Pre-assign bg25004@mbstu.ac.bd as CR of BGE 20 batch
insert into classmate.cr_allowlist(email, department_id, batch_id, student_id, academic_session, active)
values (
  'bg25004@mbstu.ac.bd',
  '7adbc189-fe0c-4012-a5f5-6dc5193ea5de', -- Biotechnology and Genetic Engineering
  '3c459208-040e-40d6-b458-1e5b82dfa67a', -- BGE 20 batch (academic session 25)
  'BG25004',
  25,
  true
)
on conflict (email) do update set
  department_id = excluded.department_id,
  batch_id = excluded.batch_id,
  student_id = excluded.student_id,
  academic_session = excluded.academic_session,
  active = true,
  updated_at = now();

notify pgrst, 'reload schema';
