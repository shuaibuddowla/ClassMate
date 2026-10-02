-- Transactional production check against real imported CSE batch 22.
-- All fixtures, notices, and outbox rows roll back before the cron can see them.
begin;
create function pg_temp.assert_true(ok boolean, message text) returns void
language plpgsql as $$ begin
  if ok is distinct from true then raise exception '%', message; end if;
end $$;

insert into classmate.departments(id,name,code,is_active)
values ('e2000000-0000-4000-8000-000000000001','Fixture department','fixturedept',true);
insert into classmate.batches(id,department_id,batch_number,academic_session)
values
  ('e2000000-0000-4000-8000-000000000002',
    (select id from classmate.departments where code='fixturedept'),23,26),
  ('e2000000-0000-4000-8000-000000000003',
    'e2000000-0000-4000-8000-000000000001',1,1);

insert into classmate.batches(id,department_id,batch_number,academic_session)
values ('e2000000-0000-4000-8000-000000000004','e2000000-0000-4000-8000-000000000001',22,26);

insert into auth.users(id,email,email_confirmed_at,aud,role)
values
  ('e2000000-0000-4000-8000-000000000011','fixture22@mbstu.ac.bd',now(),'authenticated','authenticated'),
  ('e2000000-0000-4000-8000-000000000012','fixture23@mbstu.ac.bd',now(),'authenticated','authenticated'),
  ('e2000000-0000-4000-8000-000000000013','fixturepending@mbstu.ac.bd',now(),'authenticated','authenticated'),
  ('e2000000-0000-4000-8000-000000000014','fixturecr@mbstu.ac.bd',now(),'authenticated','authenticated'),
  ('e2000000-0000-4000-8000-000000000015','fixtureteacher@mbstu.ac.bd',now(),'authenticated','authenticated'),
  ('e2000000-0000-4000-8000-000000000016','fixtureexpired@mbstu.ac.bd',now(),'authenticated','authenticated');

insert into classmate.profiles
  (id,email,role,department_id,batch_id,verification_status,is_cr,cr_batch_id,cr_valid_until)
values
  ('e2000000-0000-4000-8000-000000000011','fixture22@mbstu.ac.bd','student',
    (select id from classmate.departments where code='fixturedept'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='fixturedept' and b.batch_number=22),'active',false,null,null),
  ('e2000000-0000-4000-8000-000000000012','fixture23@mbstu.ac.bd','student',
    (select id from classmate.departments where code='fixturedept'),
    'e2000000-0000-4000-8000-000000000002','active',false,null,null),
  ('e2000000-0000-4000-8000-000000000013','fixturepending@mbstu.ac.bd','student',
    'e2000000-0000-4000-8000-000000000001',null,'pending',false,null,null),
  ('e2000000-0000-4000-8000-000000000014','fixturecr@mbstu.ac.bd','student',
    (select id from classmate.departments where code='fixturedept'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='fixturedept' and b.batch_number=22),'active',true,
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='fixturedept' and b.batch_number=22),now()+interval '1 day'),
  ('e2000000-0000-4000-8000-000000000015','fixtureteacher@mbstu.ac.bd','teacher',
    (select id from classmate.departments where code='fixturedept'),null,'active',false,null,null),
  ('e2000000-0000-4000-8000-000000000016','fixtureexpired@mbstu.ac.bd','student',
    (select id from classmate.departments where code='fixturedept'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='fixturedept' and b.batch_number=22),'active',true,
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='fixturedept' and b.batch_number=22),now()-interval '1 day');

insert into classmate.teacher_allowlist(email,department_id,created_by)
values ('fixtureteacher@mbstu.ac.bd',
  (select id from classmate.departments where code='fixturedept'),
  (select id from classmate.profiles where role='admin'));
insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
select 'e2000000-0000-4000-8000-000000000015',sc.id,
  (select id from classmate.profiles where role='admin')
from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
join classmate.batches b on b.id=s.batch_id join classmate.departments d on d.id=b.department_id
where d.code='fixturedept' and b.batch_number=22 and s.status='active'
order by sc.id limit 1;

insert into classmate.notices(id,department_id,batch_id,author_id,title)
select 'e2000000-0000-4000-8000-000000000021',b.department_id,b.id,
  (select id from classmate.profiles where role='admin'),'Production scope fixture 22'
from classmate.batches b join classmate.departments d on d.id=b.department_id
where d.code='fixturedept' and b.batch_number=22;
insert into classmate.notices(id,department_id,batch_id,author_id,title)
values
  ('e2000000-0000-4000-8000-000000000022',
    (select id from classmate.departments where code='fixturedept'),
    'e2000000-0000-4000-8000-000000000002',
    (select id from classmate.profiles where role='admin'),'Production scope fixture 23'),
  ('e2000000-0000-4000-8000-000000000023',
    'e2000000-0000-4000-8000-000000000001',
    'e2000000-0000-4000-8000-000000000003',
    (select id from classmate.profiles where role='admin'),'Production foreign fixture');

update classmate.semesters set status='active' where batch_id='e2000000-0000-4000-8000-000000000004' and semester_number=1;
insert into classmate.courses(id,department_id,course_code,course_title,course_type)
values ('e2000000-0000-4000-8000-000000000030','e2000000-0000-4000-8000-000000000001','BUSQA','QA Course','theory');
insert into classmate.semester_courses(id,semester_id,course_id)
select 'e2000000-0000-4000-8000-000000000031',s.id,'e2000000-0000-4000-8000-000000000030' from classmate.semesters s
where s.batch_id='e2000000-0000-4000-8000-000000000004' and s.status='active';
insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
select 'e2000000-0000-4000-8000-000000000015','e2000000-0000-4000-8000-000000000031',p.id from classmate.profiles p where p.role='admin';
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
select pg_temp.assert_true(classmate.can_edit_bus_schedules(),'Active CR cannot edit buses');
do $$ declare bus classmate.bus_schedules; begin
  select * into bus from classmate.save_bus_schedule(null,'QA Bus','08:00'::time,'Campus','Town',array[0,1]::smallint[],'',true);
  perform classmate.save_bus_schedule(bus.id,'QA Bus Edited','09:00'::time,'Campus','Town',array[0,1]::smallint[],'',true);
end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000011',true);
select pg_temp.assert_true(not classmate.can_edit_bus_schedules(),'Ordinary student can edit buses');
do $$ begin
  perform classmate.save_bus_schedule(null,'Forbidden','08:00'::time,'A','B',array[0]::smallint[],'',true);
  raise exception 'Student saved bus';
exception when insufficient_privilege then null; end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000016',true);
select pg_temp.assert_true(not classmate.can_edit_bus_schedules(),'Expired CR can edit buses');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000015',true);
select pg_temp.assert_true(not classmate.can_edit_bus_schedules(),'Teacher can edit buses');
select pg_temp.assert_true(classmate.can_post('e2000000-0000-4000-8000-000000000004',null),'Assigned teacher cannot post general notice');
select pg_temp.assert_true(not classmate.can_post('e2000000-0000-4000-8000-000000000002',null),'Teacher can post to unassigned batch');
select classmate.post_notice('e2000000-0000-4000-8000-000000000004',null,'QA General','QA message');
reset role;
update classmate.teacher_allowlist set active=false where email='fixtureteacher@mbstu.ac.bd';
set local role authenticated;
select pg_temp.assert_true(not classmate.can_post('e2000000-0000-4000-8000-000000000004',null),'Revoked teacher can post');
rollback;
