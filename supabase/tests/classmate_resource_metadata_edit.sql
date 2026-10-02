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

insert into classmate.file_metadata(id,department_id,batch_id,semester_course_id,uploaded_by,title,file_type,r2_object_key,mime_type,size_bytes,status)
values ('e2000000-0000-4000-8000-000000000041','e2000000-0000-4000-8000-000000000001',
'e2000000-0000-4000-8000-000000000004','e2000000-0000-4000-8000-000000000031',
(select id from classmate.profiles where role='admin'),'Original','pdf','qa/never-uploaded-edit-test','application/pdf',123,'pending');
update classmate.file_metadata set status='active' where id='e2000000-0000-4000-8000-000000000041';
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
select classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Edited by CR','slides','e2000000-0000-4000-8000-000000000031');
select pg_temp.assert_true((select title='Resource: Edited by CR' from classmate.notices where resource_id='e2000000-0000-4000-8000-000000000041'),'Linked notice was not synchronized');
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','','slides','e2000000-0000-4000-8000-000000000031');
  raise exception 'Empty title accepted';
exception when check_violation then null; end $$;
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Invalid','invalid','e2000000-0000-4000-8000-000000000031');
  raise exception 'Invalid category accepted';
exception when check_violation then null; end $$;
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Wrong subject','notes','e2000000-0000-4000-8000-000000000030');
  raise exception 'Invalid subject accepted';
exception when check_violation then null; end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000011',true);
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Forbidden','notes','e2000000-0000-4000-8000-000000000031');
  raise exception 'Student edited file';
exception when insufficient_privilege then null; end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000015',true);
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Forbidden','notes','e2000000-0000-4000-8000-000000000031');
  raise exception 'Teacher edited file';
exception when insufficient_privilege then null; end $$;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000016',true);
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Forbidden','notes','e2000000-0000-4000-8000-000000000031');
  raise exception 'Expired CR edited file';
exception when insufficient_privilege then null; end $$;
reset role;
update classmate.profiles set batch_id='e2000000-0000-4000-8000-000000000002',cr_batch_id='e2000000-0000-4000-8000-000000000002' where id='e2000000-0000-4000-8000-000000000014';
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
do $$ begin
  perform classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Forbidden','notes','e2000000-0000-4000-8000-000000000031');
  raise exception 'Foreign batch CR edited file';
exception when insufficient_privilege then null; end $$;
reset role;
select set_config('request.jwt.claim.sub',(select id::text from classmate.profiles where role='admin'),true);
set local role authenticated;
select classmate.edit_resource_metadata('e2000000-0000-4000-8000-000000000041','Edited by owner','questions','e2000000-0000-4000-8000-000000000031');
select pg_temp.assert_true((select title='Edited by owner' and r2_object_key='qa/never-uploaded-edit-test' and size_bytes=123 from classmate.file_metadata where id='e2000000-0000-4000-8000-000000000041'),'File object changed unexpectedly');
reset role;
rollback;
select 'Resource edit: owner and batch CR allowed; student, teacher, expired and foreign CR denied; input validation and linked notice verified; all fixtures rolled back.' as result;
