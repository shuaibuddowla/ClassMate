begin;

create or replace function classmate.post_cancellation_notice(
  target_batch uuid, target_course uuid, change_date date)
returns classmate.notices language plpgsql security definer set search_path = '' as $$
declare
  target_department uuid;
  course_title text;
  existing_notice classmate.notices%rowtype;
  change_row classmate.class_changes%rowtype;
begin
  if change_date not in ((now() at time zone 'Asia/Dhaka')::date,
      (now() at time zone 'Asia/Dhaka')::date + 1) then
    raise exception 'Choose today or tomorrow';
  end if;
  if target_course is null or not classmate.can_post(target_batch, target_course) then
    raise exception 'Course assignment required' using errcode = '42501';
  end if;
  select b.department_id, c.course_title into target_department, course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.batches b on b.id = s.batch_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_course and s.batch_id = target_batch and s.status = 'active';
  if target_department is null then
    raise exception 'Course semester is not active';
  end if;
  select n.* into existing_notice from classmate.notices n
  join classmate.class_changes cc on cc.id = n.class_change_id
  where cc.batch_id = target_batch and cc.semester_course_id = target_course
    and cc.effective_date = change_date and cc.kind = 'cancelled'
  order by n.published_at desc limit 1;
  if found then return existing_notice; end if;
  insert into classmate.class_changes(department_id, batch_id, semester_course_id,
    author_id, kind, effective_date, details)
  values (target_department, target_batch, target_course, auth.uid(),
    'cancelled', change_date, '') returning * into change_row;
  -- The notice below is the single notification for this action.
  delete from classmate.notification_outbox
    where kind = 'class_change' and record_id = change_row.id;
  insert into classmate.notices(department_id, batch_id, semester_course_id,
    author_id, title, body, class_change_id)
  values (target_department, target_batch, target_course, auth.uid(),
    left('Class cancelled · ' || course_title, 200),
    'Class on ' || to_char(change_date, 'DD Mon YYYY') || ' is cancelled.', change_row.id)
  returning * into existing_notice;
  return existing_notice;
end;
$$;

commit;
