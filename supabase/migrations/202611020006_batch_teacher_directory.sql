begin;

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
    raise exception 'Batch access denied' using errcode='42501';
  end if;
  return query
    select t.id,d.code,d.name,t.full_name,t.email,t.designation,t.phone,
      t.profile_url,t.source_checked_at,
      array_agg(distinct coalesce(sc.code_override,c.course_code)
        order by coalesce(sc.code_override,c.course_code))
    from classmate.semester_courses sc
    join classmate.semesters s on s.id=sc.semester_id and s.status='active'
    join classmate.courses c on c.id=sc.course_id
    join classmate.teacher_directory t on t.id=sc.teacher_record_id
    join classmate.departments d on d.id=t.department_id
    where s.batch_id=target_batch
    group by t.id,d.code,d.name,t.full_name,t.email,t.designation,t.phone,
      t.profile_url,t.source_checked_at
    order by (d.code='cse') desc,d.name,lower(t.full_name);
end;
$$;

revoke all on function classmate.batch_teachers_directory(uuid) from public,anon;
grant execute on function classmate.batch_teachers_directory(uuid) to authenticated;
notify pgrst,'reload schema';
commit;
