-- 202611070004_multitenant_available_batches_and_institution.sql
-- Expose institution metadata (short_name, weekend_days, has_bus_transit, has_academic_calendar)
-- in available_batches so web & mobile can configure schedules, weekends, and transit dynamically.

create or replace function classmate.available_batches()
returns jsonb
language plpgsql
stable security definer
set search_path TO ''
as $function$
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  return coalesce((select jsonb_agg(to_jsonb(q)) from(select 
    b.id,
    b.department_id,
    b.batch_number,
    b.academic_session,
    coalesce(b.calendar_in_timetable, true) as calendar_in_timetable,
    classmate.is_attendance_feature_enabled(b.id) as attendance_enabled,
    jsonb_build_object('code', d.code, 'name', d.name) departments,
    jsonb_build_object(
      'id', coalesce(i.id, 'a0000000-0000-0000-0000-000000000001'::uuid),
      'short_name', coalesce(i.short_name, 'MBSTU'),
      'name', coalesce(i.name, 'Mawlana Bhashani Science and Technology University'),
      'weekend_days', coalesce(i.weekend_days, '{4,5}'::int[]),
      'has_bus_transit', coalesce(i.has_bus_transit, true),
      'has_academic_calendar', coalesce(i.has_academic_calendar, true)
    ) institution
  from classmate.batches b 
  join classmate.departments d on d.id=b.department_id
  left join classmate.institutions i on i.id=d.institution_id
  where b.is_active and classmate.can_read_batch(b.id) and (
    classmate.is_owner()
    or not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher')
    or exists(
      select 1 from classmate.teacher_course_assignments a
      join classmate.semester_courses sc on sc.id=a.semester_course_id
      join classmate.semesters s on s.id=sc.semester_id
      where a.teacher_id=auth.uid() and a.active and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
    or exists(
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id=sc.semester_id
      join classmate.teacher_directory td on td.id=sc.teacher_record_id
      join classmate.profiles p on p.id=auth.uid()
      where (
        td.profile_id=auth.uid()
        or lower(td.email)=lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      ) and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
    or exists(
      select 1 from classmate.course_teachers ct
      join classmate.semester_courses sc on sc.id=ct.semester_course_id
      join classmate.semesters s on s.id=sc.semester_id
      join classmate.teacher_directory td on td.id=ct.teacher_record_id
      join classmate.profiles p on p.id=auth.uid()
      where (
        td.profile_id=auth.uid()
        or lower(td.email)=lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      ) and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
  )
  order by d.code, b.batch_number) q), '[]'::jsonb);
end $function$;

revoke all on function classmate.available_batches() from public, anon;
grant execute on function classmate.available_batches() to authenticated;
notify pgrst, 'reload schema';
