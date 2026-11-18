-- 202611060007_fix_batch_teachers_directory_groupby.sql
-- Fix: include d.institution_id in GROUP BY clause so PostgreSQL does not fail with error 42803

CREATE OR REPLACE FUNCTION classmate.batch_teachers_directory(target_batch uuid)
 RETURNS TABLE(id uuid, department_code text, department_name text, full_name text, email text, designation text, phone text, profile_url text, source_checked_at date, courses text[])
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_target_institution_id uuid;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode = '42501';
  end if;

  select d.institution_id into v_target_institution_id
  from classmate.batches b
  join classmate.departments d on d.id = b.department_id
  where b.id = target_batch;

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
      and (
        d.institution_id = v_target_institution_id
        or (v_target_institution_id is null and d.institution_id = (select inst.id from classmate.institutions inst where inst.slug = 'mbstu'))
      )
    group by
      t.id,
      d.code,
      d.name,
      d.institution_id,
      t.full_name,
      t.email,
      t.designation,
      t.phone,
      t.profile_url,
      t.source_checked_at
    order by
      (d.institution_id = v_target_institution_id) desc,
      d.name,
      lower(t.full_name);
end;
$function$;
