-- Transactional production check against real imported CSE batch 22.
-- All fixtures, notices, and outbox rows roll back before the cron can see them.
begin;
create function pg_temp.assert_true(ok boolean, message text) returns void
language plpgsql as $$ begin
  if ok is distinct from true then raise exception '%', message; end if;
end $$;

do $$ begin
  if (select count(*) from classmate.profiles where role = 'admin') <> 1
    or (select count(*) from classmate.batches b join classmate.departments d
      on d.id = b.department_id where d.code = 'cse' and b.batch_number = 22) <> 1
    or (select count(*) from classmate.semester_courses sc join classmate.semesters s
      on s.id = sc.semester_id join classmate.batches b on b.id = s.batch_id
      join classmate.departments d on d.id = b.department_id
      where d.code = 'cse' and b.batch_number = 22 and s.status = 'active') < 2 then
    raise exception 'Expected production owner, CSE 22, and two active courses';
  end if;
end $$;

insert into classmate.departments(id,name,code,is_active)
values ('e2000000-0000-4000-8000-000000000001','Fixture department','fixturedept',true);
insert into classmate.batches(id,department_id,batch_number,academic_session)
values
  ('e2000000-0000-4000-8000-000000000002',
    (select id from classmate.departments where code='cse'),23,26),
  ('e2000000-0000-4000-8000-000000000003',
    'e2000000-0000-4000-8000-000000000001',1,1);

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
    (select id from classmate.departments where code='cse'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),'active',false,null,null),
  ('e2000000-0000-4000-8000-000000000012','fixture23@mbstu.ac.bd','student',
    (select id from classmate.departments where code='cse'),
    'e2000000-0000-4000-8000-000000000002','active',false,null,null),
  ('e2000000-0000-4000-8000-000000000013','fixturepending@mbstu.ac.bd','student',
    'e2000000-0000-4000-8000-000000000001',null,'pending',false,null,null),
  ('e2000000-0000-4000-8000-000000000014','fixturecr@mbstu.ac.bd','student',
    (select id from classmate.departments where code='cse'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),'active',true,
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),now()+interval '1 day'),
  ('e2000000-0000-4000-8000-000000000015','fixtureteacher@mbstu.ac.bd','teacher',
    (select id from classmate.departments where code='cse'),null,'active',false,null,null),
  ('e2000000-0000-4000-8000-000000000016','fixtureexpired@mbstu.ac.bd','student',
    (select id from classmate.departments where code='cse'),
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),'active',true,
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),now()-interval '1 day');

insert into classmate.teacher_allowlist(email,department_id,created_by)
values ('fixtureteacher@mbstu.ac.bd',
  (select id from classmate.departments where code='cse'),
  (select id from classmate.profiles where role='admin'));
insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
select 'e2000000-0000-4000-8000-000000000015',sc.id,
  (select id from classmate.profiles where role='admin')
from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
join classmate.batches b on b.id=s.batch_id join classmate.departments d on d.id=b.department_id
where d.code='cse' and b.batch_number=22 and s.status='active'
order by sc.id limit 1;

insert into classmate.notices(id,department_id,batch_id,author_id,title)
select 'e2000000-0000-4000-8000-000000000021',b.department_id,b.id,
  (select id from classmate.profiles where role='admin'),'Production scope fixture 22'
from classmate.batches b join classmate.departments d on d.id=b.department_id
where d.code='cse' and b.batch_number=22;
insert into classmate.notices(id,department_id,batch_id,author_id,title)
values
  ('e2000000-0000-4000-8000-000000000022',
    (select id from classmate.departments where code='cse'),
    'e2000000-0000-4000-8000-000000000002',
    (select id from classmate.profiles where role='admin'),'Production scope fixture 23'),
  ('e2000000-0000-4000-8000-000000000023',
    'e2000000-0000-4000-8000-000000000001',
    'e2000000-0000-4000-8000-000000000003',
    (select id from classmate.profiles where role='admin'),'Production foreign fixture');

set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000011',true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices where id in
    ('e2000000-0000-4000-8000-000000000021',
     'e2000000-0000-4000-8000-000000000022',
     'e2000000-0000-4000-8000-000000000023')) = 1
  and not classmate.can_post(
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),null),
  'Batch 22 student scope failed');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000012',true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices where id in
    ('e2000000-0000-4000-8000-000000000021',
     'e2000000-0000-4000-8000-000000000022',
     'e2000000-0000-4000-8000-000000000023')) = 1,
  'Batch 23 student scope failed');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000013',true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices)=0
  and (select count(*) from classmate.batches)=0
  and (select count(*) from classmate.profiles)=1,
  'Pending profile leaked academic data');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
select pg_temp.assert_true(
  classmate.can_post(
    (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
      where d.code='cse' and b.batch_number=22),null)
  and not classmate.can_post('e2000000-0000-4000-8000-000000000002',null),
  'CR cross-batch authority failed');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000016',true);
select pg_temp.assert_true(not classmate.can_post(
  (select b.id from classmate.batches b join classmate.departments d on d.id=b.department_id
    where d.code='cse' and b.batch_number=22),null),
  'Expired CR still has authority');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000015',true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices where id in
    ('e2000000-0000-4000-8000-000000000021',
     'e2000000-0000-4000-8000-000000000022',
     'e2000000-0000-4000-8000-000000000023')) = 1
  and classmate.can_manage_course((select semester_course_id
    from classmate.teacher_course_assignments
    where teacher_id='e2000000-0000-4000-8000-000000000015')),
  'Teacher scope failed');
reset role;
select pg_temp.assert_true(
  (select count(*) from classmate.notification_outbox
    where record_id in ('e2000000-0000-4000-8000-000000000021',
      'e2000000-0000-4000-8000-000000000022',
      'e2000000-0000-4000-8000-000000000023')) = 3,
  'Notification outbox did not retain per-batch scope');
rollback;
