begin;

-- 0. Relax email check constraint to allow all valid official teacher emails (e.g., mbstu.ac.bd, gmail.com, yahoo.com)
alter table classmate.teacher_directory drop constraint if exists teacher_directory_email_check;
alter table classmate.teacher_directory add constraint teacher_directory_email_check check (
  email is null or (
    (email = lower(trim(email))) and 
    (email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')
  )
);

-- 1. Clean up duplicate or draft rows and populate verified teacher directory with exact MBSTU CSE faculty data
do $$
declare
  cse uuid;
  phy uuid;
  math uuid;
  chem uuid;
begin
  select id into cse from classmate.departments where code = 'cse';
  select id into phy from classmate.departments where code = 'phy';
  select id into math from classmate.departments where code = 'math';
  select id into chem from classmate.departments where code = 'chem';

  if cse is null then
    raise exception 'CSE department not found';
  end if;

  -- Upsert all verified CSE teachers with complete details
  insert into classmate.teacher_directory(department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  values
    (cse, 'Dr. Mohammad Motiur Rahman', 'motiurcse@mbstu.ac.bd', 'Professor (Grade-1)', '+8801711362344', 'https://cse.mbstu.ac.bd/teacher/dr-mohammad-motiur-rahman', '2026-11-02'),
    (cse, 'Dr. Mostofa Kamal Nasir', 'kamal@mbstu.ac.bd', 'Professor (Grade-1)', '+8801716056915', 'https://cse.mbstu.ac.bd/teacher/dr-mostofa-kamal-nasir', '2026-11-02'),
    (cse, 'Dr. Md. Sazzad Hossain', 'sazzad@mbstu.ac.bd', 'Professor', '+8801716862814', 'https://cse.mbstu.ac.bd/teacher/dr-md-sazzad-hossain', '2026-11-02'),
    (cse, 'Dr. Mehedi Hasan Talukder', 'mehedi@mbstu.ac.bd', 'Professor', '+8801717562621', 'https://cse.mbstu.ac.bd/teacher/dr-mehedi-hasan-talukder', '2026-11-02'),
    (cse, 'Dr. Mahbuba Begum', 'mahbubacse@mbstu.ac.bd', 'Professor', '+8801721528854', 'https://cse.mbstu.ac.bd/teacher/dr-mahbuba-begum', '2026-11-02'),
    (cse, 'Mohammed Hadifur Rahman', 'hadifur@gmail.com', 'Associate Professor', '+8801716517171', 'https://cse.mbstu.ac.bd/teacher/mohammed-hadifur-rahman', '2026-11-02'),
    (cse, 'Md. Mosaddik Hasan', 'mosaddik.hasan@mbstu.ac.bd', 'Associate Professor', '+8801715455722', 'https://cse.mbstu.ac.bd/teacher/md-mosaddik-hasan', '2026-11-02'),
    (cse, 'Md. Mahfuz Reza', 'mahfuz@mbstu.ac.bd', 'Associate Professor', '+8801722410844', 'https://cse.mbstu.ac.bd/teacher/md-mahfuz-reza', '2026-11-02'),
    (cse, 'A S M Delowar Hossain', 'delowar@mbstu.ac.bd', 'Associate Professor', '+8801713585542', 'https://cse.mbstu.ac.bd/teacher/a-s-m-delowar-hossain', '2026-11-02'),
    (cse, 'Lubna Yasmin Pinky', 'lubnacse@mbstu.ac.bd', 'Associate Professor', '+8801825737424', 'https://cse.mbstu.ac.bd/teacher/lubna-yasmin-pinky', '2026-11-02'),
    (cse, 'Shisir Mia', 'shisir.cse@mbstu.ac.bd', 'Assistant Professor', '+8801772828537', 'https://cse.mbstu.ac.bd/teacher/shisir-mia', '2026-11-02'),
    (cse, 'Md. Mazder Rahman', 'mazdercsdu2000@yahoo.com', 'Assistant Professor', null, 'https://cse.mbstu.ac.bd/teacher/md-mazder-rahman', '2026-11-02'),
    (cse, 'Md. Belayet Ali', 'belayet_2003@yahoo.com', 'Assistant Professor', null, 'https://cse.mbstu.ac.bd/teacher/md-belayet-ali', '2026-11-02'),
    (cse, 'Md. Rashedul Hasan', 'rakib_cse02@yahoo.com', 'Assistant Professor', null, 'https://cse.mbstu.ac.bd/teacher/md-rashedul-hasan', '2026-11-02'),
    (cse, 'Dr. Md. Asaduzzaman', 'parvez_ku_01@yahoo.com', 'Lecturer', null, 'https://cse.mbstu.ac.bd/teacher/dr-md-asaduzzaman', '2026-11-02'),
    (cse, 'A.K.M. Kamrul Islam', 'kamrul3000@gmail.com', 'Lecturer', null, 'https://cse.mbstu.ac.bd/teacher/akm-kamrul-islam', '2026-11-02'),
    (cse, 'Md. Mezbahul Islam', 'mailformezbah@gmail.com', 'Assistant Professor (on leave)', '+8801920446868', 'https://cse.mbstu.ac.bd/teacher/md-mezbahul-islam', '2026-11-02'),
    (cse, 'Mohd. Sultan Ahammad', 'sultan.ahammad@mbstu.ac.bd', 'Assistant Professor (on leave)', '+8801734095495', 'https://cse.mbstu.ac.bd/teacher/mohd-sultan-ahammad', '2026-11-02'),
    (cse, 'Rahmina Rubaiat', 'rahmina@mbstu.ac.bd', 'Lecturer', '+8801915545288', 'https://cse.mbstu.ac.bd/faculty-members', '2026-11-02')
  on conflict(department_id, lower(trim(full_name))) do update set
    email = excluded.email,
    designation = excluded.designation,
    phone = coalesce(excluded.phone, classmate.teacher_directory.phone),
    profile_url = excluded.profile_url,
    source_checked_at = excluded.source_checked_at,
    updated_at = now();

  -- Supporting science faculty
  if phy is not null then
    insert into classmate.teacher_directory(department_id, full_name, email, designation, phone, profile_url, source_checked_at)
    values (phy, 'MD. ASHRAFUL ALAM', null, 'Associate Professor', '+880 1884774326', 'https://phy.mbstu.ac.bd/teacher/md-ashraful-alam', '2026-11-02')
    on conflict(department_id, lower(trim(full_name))) do update set
      designation = excluded.designation,
      phone = excluded.phone,
      profile_url = excluded.profile_url,
      source_checked_at = excluded.source_checked_at,
      updated_at = now();
  end if;

  if math is not null then
    insert into classmate.teacher_directory(department_id, full_name, email, designation, phone, profile_url, source_checked_at)
    values
      (math, 'Dr. Mohammad Mokaddes Ali', 'mmali309.math@mbstu.ac.bd', 'Professor', null, 'https://math.mbstu.ac.bd', '2026-11-02'),
      (math, 'Mr. Md. Shah Alam', null, 'Associate Professor (on study leave)', null, 'https://math.mbstu.ac.bd/teacher/mr-md-shah-alam', '2026-11-02')
    on conflict(department_id, lower(trim(full_name))) do update set
      email = coalesce(excluded.email, classmate.teacher_directory.email),
      designation = excluded.designation,
      profile_url = excluded.profile_url,
      source_checked_at = excluded.source_checked_at,
      updated_at = now();
  end if;
end $$;

-- 2. Enhance batch_teachers_directory with LEFT JOIN so ALL MBSTU teachers are returned,
-- while courses for the queried batch are attached.
create or replace function classmate.batch_teachers_directory(target_batch uuid)
returns table(
  id uuid,
  department_code text,
  department_name text,
  full_name text,
  email text,
  designation text,
  phone text,
  profile_url text,
  source_checked_at date,
  courses text[]
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode = '42501';
  end if;

  return query
    select
      t.id,
      d.code as department_code,
      d.name as department_name,
      t.full_name,
      t.email,
      t.designation,
      t.phone,
      t.profile_url,
      t.source_checked_at,
      coalesce(
        array_remove(
          array_agg(distinct coalesce(sc.code_override, c.course_code) order by coalesce(sc.code_override, c.course_code)),
          null
        ),
        array[]::text[]
      ) as courses
    from classmate.teacher_directory t
    join classmate.departments d on d.id = t.department_id
    left join classmate.semester_courses sc on sc.teacher_record_id = t.id
    left join classmate.semesters s on s.id = sc.semester_id and s.batch_id = target_batch and s.status = 'active'
    left join classmate.courses c on c.id = sc.course_id
    where t.full_name !~* '^(CSE batch|Test|Dummy)'
    group by
      t.id,
      d.code,
      d.name,
      t.full_name,
      t.email,
      t.designation,
      t.phone,
      t.profile_url,
      t.source_checked_at
    order by
      (d.code = 'cse') desc,
      d.name,
      lower(t.full_name);
end;
$$;

-- 3. Dedicated assignable_teachers RPC for CRs and Admins to select teachers when configuring courses
create or replace function classmate.assignable_teachers(target_batch uuid default null)
returns table(
  id uuid,
  full_name text,
  email text,
  designation text,
  department_code text,
  department_name text
)
language plpgsql stable security definer set search_path = '' as $$
declare
  caller classmate.profiles%rowtype;
begin
  select * into caller from classmate.profiles p where p.id = auth.uid() and p.verification_status = 'active';
  if caller.id is null or not classmate.is_active() then
    raise exception 'Active profile required' using errcode = '42501';
  end if;

  return query
    select
      t.id,
      t.full_name,
      t.email,
      t.designation,
      d.code as department_code,
      d.name as department_name
    from classmate.teacher_directory t
    join classmate.departments d on d.id = t.department_id
    where t.full_name !~* '^(CSE batch|Test|Dummy)'
    order by
      (d.code = 'cse') desc,
      d.name,
      lower(t.full_name);
end;
$$;

revoke all on function classmate.assignable_teachers(uuid) from public, anon;
grant execute on function classmate.assignable_teachers(uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
