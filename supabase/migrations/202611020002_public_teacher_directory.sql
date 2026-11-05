begin;
alter table classmate.teacher_directory alter column created_by drop not null;
alter table classmate.teacher_directory
  add column designation text,
  add column phone text,
  add column profile_url text,
  add column source_checked_at date;
create unique index teacher_directory_dept_name_ci
  on classmate.teacher_directory(department_id, lower(trim(full_name)));
drop policy if exists teacher_directory_read on classmate.teacher_directory;
create policy teacher_directory_read on classmate.teacher_directory for select to authenticated
  using(classmate.is_active());

create function classmate.faculty_directory()
returns table(id uuid,department_code text,department_name text,full_name text,email text,
  designation text,phone text,profile_url text,source_checked_at date)
language sql stable security definer set search_path='' as $$
  select t.id,d.code,d.name,t.full_name,t.email,t.designation,t.phone,t.profile_url,t.source_checked_at
  from classmate.teacher_directory t join classmate.departments d on d.id=t.department_id
  where classmate.is_active()
  order by (d.code='cse') desc,d.name,lower(t.full_name);
$$;
revoke all on function classmate.faculty_directory() from public,anon;
grant execute on function classmate.faculty_directory() to authenticated;

do $$ declare cse uuid; begin
  insert into classmate.departments(name,code,is_active) values
    ('Physics','phy',false),('Chemistry','chem',false),('Mathematics','math',false)
  on conflict(code) do nothing;
  select id into cse from classmate.departments where code='cse';
  if cse is null then raise exception 'CSE department missing'; end if;
  insert into classmate.teacher_directory(department_id,full_name,email,designation,phone,profile_url,source_checked_at)
  values
    (cse,'Dr. Mohammad Motiur Rahman',null,'Professor (Grade-1)','+8801711362344','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Dr. Mostofa Kamal Nasir','kamal@mbstu.ac.bd','Professor','+8801716056915','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Dr. Md. Sazzad Hossain',null,'Professor','+8801716862814','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Dr. Mehedi Hasan Talukder','mehedi@mbstu.ac.bd','Professor','+8801717562621','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Hadifur Rahman',null,'Associate Professor','+8801716517171','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Mosaddik Hasan','mosaddik.hasan@mbstu.ac.bd','Associate Professor','+8801715455722','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Mahfuz Reza','mahfuz@mbstu.ac.bd','Associate Professor','+8801722410844','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Dr. Mahbuba Begum','mahbubacse@mbstu.ac.bd','Professor','+8801721528854','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'A S M Delowar Hossain','delowar@mbstu.ac.bd','Associate Professor','+8801713585542','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Lubna Yasmin Pinky','lubnacse@mbstu.ac.bd','Assistant Professor','+8801825737424','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Shisir Mia',null,'Assistant Professor','+8801772828537','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Mazder Rahman',null,'Assistant Professor',null,'https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Belayet Ali',null,'Assistant Professor',null,'https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Rashedul Hasan',null,'Assistant Professor',null,'https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Dr. Md. Asaduzzaman',null,'Lecturer',null,'https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'A.K.M. Kamrul Islam',null,'Lecturer',null,'https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Md. Mezbahul Islam',null,'Assistant Professor','+8801920446868','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Mohd. Sultan Ahammad','sultan.ahammad@mbstu.ac.bd','Assistant Professor','+8801734095495','https://www.cse.mbstu.ac.bd/teachers','2026-11-02'),
    (cse,'Rahmina Rubaiat','rahmina@mbstu.ac.bd','Lecturer','+8801915545288','https://www.cse.mbstu.ac.bd/teachers','2026-11-02')
  on conflict(department_id,lower(trim(full_name))) do update set
    email=coalesce(excluded.email,classmate.teacher_directory.email),
    designation=excluded.designation,phone=coalesce(excluded.phone,classmate.teacher_directory.phone),profile_url=excluded.profile_url,source_checked_at=excluded.source_checked_at,
    updated_at=now();
  insert into classmate.teacher_directory(department_id,full_name,designation,phone,profile_url,source_checked_at)
  select d.id,'MD. ASHRAFUL ALAM','Associate Professor','+880 1884774326',
    'https://phy.mbstu.ac.bd/teacher/md-ashraful-alam','2026-11-02'
    from classmate.departments d where d.code='phy'
  on conflict(department_id,lower(trim(full_name))) do update set designation=excluded.designation,
    phone=excluded.phone,profile_url=excluded.profile_url,source_checked_at=excluded.source_checked_at,updated_at=now();
  insert into classmate.teacher_directory(department_id,full_name,designation,profile_url,source_checked_at)
  select d.id,'Mr. Md. Shah Alam','Associate Professor (on study leave)',
    'https://math.mbstu.ac.bd/teacher/mr-md-shah-alam','2026-11-02'
    from classmate.departments d where d.code='math'
  on conflict(department_id,lower(trim(full_name))) do update set designation=excluded.designation,
    profile_url=excluded.profile_url,source_checked_at=excluded.source_checked_at,updated_at=now();
end $$;
notify pgrst,'reload schema';
commit;
