-- Rollback-only test using isolated department, batches and synthetic device tokens.
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


insert into classmate.device_tokens(profile_id,fcm_token)
select id,'isolated-qa-not-a-real-device-'||id::text from classmate.profiles where id::text like 'e2000000-%';
insert into classmate.device_tokens(profile_id,fcm_token)
select id,'isolated-qa-owner-not-a-real-device' from classmate.profiles where role='admin';
select pg_temp.assert_true((select count(*)=4 from classmate.notification_recipients((select id from classmate.notification_outbox where record_id='e2000000-0000-4000-8000-000000000021'))),'Batch 22 recipients should be 3 active students plus its assigned teacher');
select pg_temp.assert_true((select count(*)=1 from classmate.notification_recipients((select id from classmate.notification_outbox where record_id='e2000000-0000-4000-8000-000000000022'))),'Cross-batch recipients leaked');
select pg_temp.assert_true(not exists(select 1 from classmate.notification_recipients((select id from classmate.notification_outbox where record_id='e2000000-0000-4000-8000-000000000021')) r join classmate.profiles p on p.id=r.profile_id where p.role='admin' or p.verification_status<>'active'),'Owner or pending account received batch notifications');
update classmate.teacher_allowlist set active=false where email='fixtureteacher@mbstu.ac.bd';
select pg_temp.assert_true((select count(*)=3 from classmate.notification_recipients((select id from classmate.notification_outbox where record_id='e2000000-0000-4000-8000-000000000021'))),'Revoked teacher received notification');
update classmate.teacher_allowlist set active=true where email='fixtureteacher@mbstu.ac.bd';
update classmate.batches set is_active=false where id='e2000000-0000-4000-8000-000000000004';
select pg_temp.assert_true(not exists(select 1 from classmate.notification_recipients((select id from classmate.notification_outbox where record_id='e2000000-0000-4000-8000-000000000021'))),'Inactive batch received notification');
update classmate.batches set is_active=true where id='e2000000-0000-4000-8000-000000000004';
-- Exercise the application's actual CR posting RPC and its automatic trigger.
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
select classmate.post_notice('e2000000-0000-4000-8000-000000000004',null,'Notification CR write fixture','Rollback-only check');
reset role;
select pg_temp.assert_true(exists(select 1 from classmate.notification_outbox o join classmate.notices n on n.id=o.record_id where n.title='Notification CR write fixture' and n.batch_id='e2000000-0000-4000-8000-000000000004'),'CR posting did not enqueue automatic delivery');
delete from classmate.notification_outbox o using classmate.notices n where o.record_id=n.id and n.title='Notification CR write fixture' and n.batch_id='e2000000-0000-4000-8000-000000000004';
delete from classmate.notices where title='Notification CR write fixture' and batch_id='e2000000-0000-4000-8000-000000000004';
-- Isolate claims from existing queue entries inside this rollback-only transaction.
update classmate.notification_outbox set delivered_at=now() where record_id::text not like 'e2000000-%';
select pg_temp.assert_true((select count(*)=3 from classmate.claim_notification_events(20)),'Could not claim test events');
select pg_temp.assert_true(not exists(select 1 from classmate.claim_notification_events(20)),'Concurrent worker reclaimed leased events');
update classmate.notification_outbox set processing_until=now()-interval '1 second' where record_id::text like 'e2000000-%';
select pg_temp.assert_true((select count(*)=3 from classmate.claim_notification_events(20)),'Expired lease could not retry');
select pg_temp.assert_true((select bool_and(attempts=2) from classmate.notification_outbox where record_id::text like 'e2000000-%'),'Attempt count incorrect');
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000011',true);
do $$ begin
 begin perform classmate.claim_notification_events(20); raise exception 'Student could claim queue'; exception when insufficient_privilege then null; end;
 begin perform classmate.notification_recipients('00000000-0000-0000-0000-000000000000'); raise exception 'Student could enumerate device tokens'; exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
select 'Batch notification isolation, teacher revocation, leases and RPC permissions passed' as result;
