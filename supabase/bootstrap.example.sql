-- ClassMate V2 owner bootstrap template.
-- Copy this file to supabase/bootstrap.local.sql, replace every CHANGE_ME value,
-- review it, and run the copy only in a disposable Supabase project first.
-- bootstrap.local.sql is gitignored because it contains real institutional data.

begin;

do $$
declare
  owner_email constant text := 'CHANGE_ME_OWNER@mbstu.ac.bd';
  university_code constant text := 'MBSTU';
  university_name constant text := 'Mawlana Bhashani Science and Technology University';
  university_domain constant text := 'mbstu.ac.bd';
  department_code constant text := 'CHANGE_ME_DEPARTMENT_CODE';
  department_name constant text := 'CHANGE_ME_DEPARTMENT_NAME';
  department_email_prefix constant text := 'CHANGE_ME_PREFIX';
  batch_code constant text := 'CHANGE_ME_TWO_DIGIT_BATCH';
  batch_name constant text := 'CHANGE_ME_BATCH_DISPLAY_NAME';
  batch_admission_year constant smallint := 0;
  university_id uuid;
  department_id uuid;
  batch_id uuid;
begin
  if owner_email like 'CHANGE_ME_%'
     or department_code like 'CHANGE_ME_%'
     or department_email_prefix like 'CHANGE_ME_%'
     or batch_code like 'CHANGE_ME_%'
     or batch_admission_year = 0 then
    raise exception 'Replace every CHANGE_ME value before running this script';
  end if;

  insert into public.universities (code, name, email_domain)
  values (university_code, university_name, university_domain)
  on conflict (code) do update set
    name = excluded.name,
    email_domain = excluded.email_domain,
    is_active = true
  returning id into university_id;

  insert into public.departments (university_id, code, name, email_prefix)
  values (university_id, department_code, department_name, department_email_prefix)
  on conflict (university_id, code) do update set
    name = excluded.name,
    email_prefix = excluded.email_prefix,
    is_active = true
  returning id into department_id;

  insert into public.semesters (university_id, ordinal, name)
  select university_id, ordinal, 'Semester ' || ordinal
  from generate_series(1, 8) as ordinal
  on conflict (university_id, ordinal) do update set name = excluded.name;

  insert into public.batches (
    department_id, cohort_code, display_name, admission_year
  ) values (
    department_id, batch_code, batch_name, batch_admission_year
  )
  on conflict (department_id, cohort_code) do update set
    display_name = excluded.display_name,
    admission_year = excluded.admission_year,
    is_archived = false
  returning id into batch_id;

  insert into public.sections (batch_id, code, name)
  values (batch_id, 'A', 'Section A')
  on conflict (batch_id, code) do update set name = excluded.name, is_active = true;

  insert into public.staff_allowlist (
    university_id, email, role, department_id, is_active, notes
  ) values (
    university_id, lower(owner_email), 'admin', null, true,
    'Initial global administrator'
  )
  on conflict (email) do update set
    university_id = excluded.university_id,
    role = 'admin',
    department_id = null,
    is_active = true,
    notes = excluded.notes;
end;
$$;

commit;
