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

insert into classmate.notice_reads(notice_id,profile_id)
select 'e2000000-0000-4000-8000-000000000021', p.id from classmate.profiles p
where p.id in ('e2000000-0000-4000-8000-000000000011','e2000000-0000-4000-8000-000000000014',
'e2000000-0000-4000-8000-000000000015','e2000000-0000-4000-8000-000000000016') or p.role='admin';
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000011',true);
select pg_temp.assert_true((select count(*) from classmate.notice_readers('e2000000-0000-4000-8000-000000000021')) = 5, 'Student cannot see readers');
select pg_temp.assert_true((select count(*) from classmate.notice_reader_previews(array[
'e2000000-0000-4000-8000-000000000021'::uuid,'e2000000-0000-4000-8000-000000000022'::uuid])) = 4, 'Preview limit or isolation failed');
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000012',true);
select pg_temp.assert_true((select count(*) from classmate.notice_reader_previews(array['e2000000-0000-4000-8000-000000000021'::uuid])) = 0, 'Cross batch previews leaked');
do $$ begin
  perform count(*) from classmate.notice_readers('e2000000-0000-4000-8000-000000000021');
  raise exception 'Cross batch readers leaked';
exception when insufficient_privilege then null; end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000013',true);
do $$ begin
  perform count(*) from classmate.notice_readers('e2000000-0000-4000-8000-000000000021');
  raise exception 'Pending account accessed readers';
exception when insufficient_privilege then null; end $$;
rollback;
