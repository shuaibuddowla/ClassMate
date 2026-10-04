begin;
-- A set-returning catalog must be aggregated before embedding it in one context.
create or replace function classmate.ai_context(target_batch uuid,query_text text default '') returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare owner boolean:=classmate.is_owner(); answer jsonb;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Batch access denied' using errcode='42501'; end if;
  answer:=jsonb_build_object('today',(now() at time zone 'Asia/Dhaka')::date,'batch_id',target_batch,
    'owner',owner,'can_write',classmate.ai_can_write(target_batch),'courses',coalesce((select jsonb_agg(to_jsonb(c)) from (select * from classmate.batch_course_catalog(target_batch) limit 100) c),'[]'::jsonb),
    'buses',coalesce((select jsonb_agg(to_jsonb(b) order by b.departure_time) from classmate.bus_schedules b where b.active),'[]'),
    'routine',coalesce((select jsonb_agg(to_jsonb(q)) from (select r.* from classmate.routine_slots r
      join classmate.semester_courses c on c.id=r.semester_course_id join classmate.semesters s on s.id=c.semester_id
      where s.batch_id=target_batch and s.status='active' order by r.day_of_week,r.start_time limit 100) q),'[]'),
    'calendar',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,start_date,end_date,scope from classmate.academic_calendar_events
      where end_date>=(now() at time zone 'Asia/Dhaka')::date-30 order by start_date limit 60) q),'[]'),
    'notices',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,body,semester_course_id,class_change_id,published_at from classmate.notices
      where batch_id=target_batch and classmate.can_read_notice(id) and (query_text='' or title ilike '%'||query_text||'%')
      order by published_at desc limit 20) q),'[]'),
    'files',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,category,semester_course_id from classmate.file_metadata
      where batch_id=target_batch and status='active' and (query_text='' or title ilike '%'||query_text||'%')
        and (semester_course_id is null or classmate.can_manage_course(semester_course_id)
          or exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='student' and p.batch_id=target_batch))
        and (semester_course_id is null or exists(select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id where sc.id=semester_course_id and s.status in ('active','completed')))
      limit 50) q),'[]'));
  if owner then
    answer:=answer||jsonb_build_object('teachers',classmate.owner_teachers(),'health',classmate.system_health(),
      'departments',coalesce((select jsonb_agg(to_jsonb(d)) from classmate.departments d),'[]'),
      'batches',coalesce((select jsonb_agg(to_jsonb(b)) from classmate.batches b),'[]'),
      'semesters',coalesce((select jsonb_agg(to_jsonb(s)) from classmate.semesters s where batch_id=target_batch),'[]'),
      'people',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,email,full_name,student_id,batch_id,role,is_cr,verification_status
        from classmate.profiles where query_text='' or full_name ilike '%'||query_text||'%' or email ilike '%'||query_text||'%' order by id limit 50) q),'[]'));
  end if;
  return answer;
end $$;
notify pgrst,'reload schema';
commit;
