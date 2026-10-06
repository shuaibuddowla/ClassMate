-- Full-batch timetable viewing includes cancellation markers without exposing notices.
begin;
create or replace function classmate.timetable_cancellations(target_batch uuid,target_start date,target_end date)
returns table(semester_course_id uuid,effective_date date)
language plpgsql stable security definer set search_path='' as $$
begin
 if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
  raise exception 'Batch access denied' using errcode='42501'; end if;
 if target_start is null or target_end is null or target_end<target_start or target_end-target_start>31 then
  raise exception 'Choose a valid timetable date range' using errcode='22023'; end if;
 return query select distinct cc.semester_course_id,cc.effective_date
 from classmate.class_changes cc join classmate.semester_courses sc on sc.id=cc.semester_course_id
 join classmate.semesters s on s.id=sc.semester_id
 where cc.batch_id=target_batch and s.batch_id=target_batch and s.status='active'
 and cc.kind='cancelled' and cc.effective_date between target_start and target_end;
end $$;
revoke all on function classmate.timetable_cancellations(uuid,date,date) from public,anon;
grant execute on function classmate.timetable_cancellations(uuid,date,date) to authenticated;
notify pgrst,'reload schema';
commit;
