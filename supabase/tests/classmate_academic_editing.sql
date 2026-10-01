-- Run against staging. Every mutation is rolled back.
begin;
do $$
declare test_department_id uuid;
declare test_batch_id uuid;
declare test_semester_id uuid;
declare test_course_id uuid;
declare owner_id uuid;
begin
  select p.id into owner_id from classmate.profiles p
    join classmate.app_owners o on o.email = p.email where o.active limit 1;
  insert into classmate.departments(name,code,is_active)
    values ('QA Editing', 'qaediting', true) returning id into test_department_id;
  insert into classmate.batches(department_id,batch_number,academic_session)
    values (test_department_id,1,1) returning id into test_batch_id;
  select id into test_semester_id from classmate.semesters
    where classmate.semesters.batch_id = test_batch_id and semester_number = 1;
  update classmate.semesters set status = 'active' where id = test_semester_id;
  insert into classmate.courses(department_id,course_code,course_title,course_type)
    values (test_department_id,'QATEST','Editing Test','theory') returning id into test_course_id;
  insert into classmate.semester_courses(semester_id,course_id)
    values (test_semester_id,test_course_id);
  insert into classmate.notices(department_id,batch_id,author_id,title,body)
    values (test_department_id,test_batch_id,owner_id,'Editing test notice','Test body');
end $$;
select set_config('request.jwt.claim.sub', u.id::text, true)
from auth.users u join classmate.app_owners o on lower(u.email) = o.email
where o.active and u.email_confirmed_at is not null limit 1;
set local role authenticated;
do $$
declare course_id uuid;
declare period_id uuid;
declare notice_id uuid;
declare title_before text;
declare body_before text;
begin
  if not classmate.is_owner() then raise exception 'Owner fixture missing'; end if;
  select sc.id into course_id from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    where c.course_code = 'QATEST' limit 1;
  if course_id is null then raise exception 'Course fixture missing'; end if;
  select (classmate.save_routine_slot(null, course_id, 0::smallint, '08:00', '08:45', 'Test Room')).id
    into period_id;
  perform classmate.save_routine_slot(period_id, course_id, 0::smallint, '08:15', '09:00', 'Test Room 2');
  if not exists (select 1 from classmate.routine_slots where id = period_id
      and room = 'Test Room 2') then raise exception 'Routine edit failed'; end if;
  perform classmate.delete_routine_slot(period_id);
  if exists (select 1 from classmate.routine_slots where id = period_id) then
    raise exception 'Routine delete failed';
  end if;
  select id,title,body into notice_id,title_before,body_before from classmate.notices
    where title = 'Editing test notice' limit 1;
  if notice_id is not null then
    perform classmate.edit_notice(notice_id, 'Temporary editing test', body_before);
    if not exists (select 1 from classmate.notices where id = notice_id
        and title = 'Temporary editing test') then raise exception 'Notice edit failed'; end if;
    perform classmate.delete_notice(notice_id);
    if exists (select 1 from classmate.notices where id = notice_id) then
      raise exception 'Notice delete failed';
    end if;
  end if;
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000000', true);
  begin
    perform classmate.save_routine_slot(null, course_id, 0::smallint,
      '08:00', '08:45', 'Denied');
    raise exception 'Unauthorized routine write was accepted';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
