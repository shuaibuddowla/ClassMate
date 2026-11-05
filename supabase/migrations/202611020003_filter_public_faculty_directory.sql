begin;
create or replace function classmate.faculty_directory()
returns table(id uuid,department_code text,department_name text,full_name text,email text,
  designation text,phone text,profile_url text,source_checked_at date)
language sql stable security definer set search_path='' as $$
  select t.id,d.code,d.name,t.full_name,t.email,t.designation,t.phone,t.profile_url,t.source_checked_at
  from classmate.teacher_directory t join classmate.departments d on d.id=t.department_id
  where classmate.is_active() and (
    t.profile_url is not null or exists(
      select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
      where sc.teacher_record_id=t.id and s.status='active'
    )
  )
  order by (d.code='cse') desc,d.name,lower(t.full_name);
$$;
notify pgrst,'reload schema';
commit;
