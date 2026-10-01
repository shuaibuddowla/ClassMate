-- Transactional staging test; no fixture data remains after rollback.
begin;
do $$
declare d uuid; b uuid; s uuid; c uuid; owner_id uuid;
begin
  select p.id into owner_id from classmate.profiles p
    join classmate.app_owners o on o.email = p.email where o.active limit 1;
  if owner_id is null then raise exception 'Owner fixture missing'; end if;
  insert into classmate.departments(name,code,is_active)
    values ('QA Cancellation', 'qacancel', true) returning id into d;
  insert into classmate.batches(department_id,batch_number,academic_session)
    values (d,1,1) returning id into b;
  select id into s from classmate.semesters where batch_id = b and semester_number = 1;
  update classmate.semesters set status = 'active' where id = s;
  insert into classmate.courses(department_id,course_code,course_title,course_type)
    values (d,'QACANCEL','Cancellation Test','theory') returning id into c;
  insert into classmate.semester_courses(semester_id,course_id) values (s,c);
end $$;
select set_config('request.jwt.claim.sub', u.id::text, true)
from auth.users u join classmate.app_owners o on lower(u.email) = o.email
where o.active and u.email_confirmed_at is not null limit 1;
set local role authenticated;
do $$
declare b uuid; sc uuid; n classmate.notices%rowtype; target_date date;
begin
  if not classmate.is_owner() then raise exception 'Owner authentication missing'; end if;
  select s.batch_id, x.id into b, sc from classmate.semester_courses x
    join classmate.semesters s on s.id = x.semester_id
    join classmate.courses c on c.id = x.course_id where c.course_code = 'QACANCEL';
  target_date := (now() at time zone 'Asia/Dhaka')::date;
  n := classmate.post_cancellation_notice(b,sc,target_date);
  if n.class_change_id is null or not exists (
    select 1 from classmate.class_changes cc where cc.id = n.class_change_id
      and cc.kind = 'cancelled' and cc.effective_date = target_date) then
    raise exception 'Cancellation was not linked to the notice';
  end if;
  perform set_config('classmate.qa_notice_id', n.id::text, true);
  perform set_config('classmate.qa_change_id', n.class_change_id::text, true);
  if not exists (select 1 from classmate.timetable_details(b,target_date,array[sc]) t
    where t.semester_course_id = sc and t.cancelled and t.teacher_name is null) then
    raise exception 'Timetable cancellation detail missing';
  end if;
  if not exists (select 1 from classmate.notice_author_details(array[n.id]) a
    where a.notice_id = n.id and a.author_name is not null) then
    raise exception 'Notice author detail missing';
  end if;
  if (classmate.post_cancellation_notice(b,sc,target_date)).id <> n.id then
    raise exception 'Duplicate cancellation notice created';
  end if;
end $$;
reset role;
do $$
begin
  if (select count(*) from classmate.notification_outbox o
      where (o.kind = 'notice' and o.record_id = current_setting('classmate.qa_notice_id')::uuid)
        or (o.kind = 'class_change' and
          o.record_id = current_setting('classmate.qa_change_id')::uuid)) <> 1 then
    raise exception 'Cancellation queued more than one notification';
  end if;
end $$;
set local role authenticated;
do $$
declare b uuid; sc uuid; target_date date;
begin
  select s.batch_id, x.id into b, sc from classmate.semester_courses x
    join classmate.semesters s on s.id = x.semester_id
    join classmate.courses c on c.id = x.course_id where c.course_code = 'QACANCEL';
  target_date := (now() at time zone 'Asia/Dhaka')::date;
  perform classmate.delete_notice(current_setting('classmate.qa_notice_id')::uuid);
  if exists (select 1 from classmate.timetable_details(b,target_date,array[sc]) t
      where t.semester_course_id = sc and t.cancelled) then
    raise exception 'Deleted cancellation still marks timetable';
  end if;
end $$;
rollback;
