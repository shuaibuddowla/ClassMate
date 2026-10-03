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

-- Existing receipts survive expansion, even when the old event exceeded ten runs.
update classmate.notification_outbox set delivered_at=now() where record_id::text not like 'e2000000-%';
update classmate.notification_outbox set attempts=999 where record_id::text like 'e2000000-%';
insert into classmate.notification_deliveries(outbox_id,profile_id,token_hash)
select e.id,dt.profile_id,dt.token_hash from classmate.notification_outbox e join classmate.device_tokens dt on dt.profile_id='e2000000-0000-4000-8000-000000000014' where e.record_id='e2000000-0000-4000-8000-000000000021';
select classmate.prepare_notification_jobs(20);
select pg_temp.assert_true((select count(*)=1 from classmate.notification_jobs where state='accepted' and event_id in(select id from classmate.notification_outbox where record_id::text like 'e2000000-%')),'Existing receipt was not preserved');
create temporary table claimed_jobs as select * from classmate.claim_notification_jobs(100);
select pg_temp.assert_true((select count(*)=4 from claimed_jobs),'Chunk progress was stopped by old attempt limit');
select pg_temp.assert_true(not exists(select 1 from classmate.claim_notification_jobs(100)),'Concurrent worker stole active leases');
select pg_temp.assert_true(not exists(select 1 from claimed_jobs c join classmate.notification_outbox e on e.id=c.event_id where e.record_id='e2000000-0000-4000-8000-000000000021' and c.profile_id='e2000000-0000-4000-8000-000000000012'),'Cross-batch device leaked');
-- Simulated worker crash: expiration changes the lease, without consuming failures.
update classmate.notification_jobs set lease_until=now()-interval '1 second' where profile_id='e2000000-0000-4000-8000-000000000011';
create temporary table reclaimed as select * from classmate.claim_notification_jobs(100);
select pg_temp.assert_true((select count(*)=1 from reclaimed),'Expired device lease did not recover');
select pg_temp.assert_true((select bool_and(failures=0) from classmate.notification_jobs where event_id in(select event_id from claimed_jobs)),'Progress counted as failure');
select pg_temp.assert_true(not classmate.finish_notification_job(event_id,token_hash,lease_id,'accepted') ,'Stale worker acknowledged a newer lease') from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000011';
-- Current teacher eligibility is checked after the claim.
update classmate.teacher_allowlist set active=false where email='fixtureteacher@mbstu.ac.bd';
select pg_temp.assert_true(classmate.notification_job_context(event_id,token_hash,lease_id) is null,'Revoked teacher remained eligible') from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000015';
update classmate.teacher_allowlist set active=true where email='fixtureteacher@mbstu.ac.bd';
select pg_temp.assert_true(classmate.notification_job_context(event_id,token_hash,lease_id) is not null,'Allowed teacher context missing') from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000015';
-- Server-side retry scheduling and token invalidation.
select classmate.finish_notification_job(event_id,token_hash,lease_id,'retry','FCM_429',120) from reclaimed;
select pg_temp.assert_true((select bool_and(failures=1 and next_attempt_at>=now()+interval '120 seconds') from classmate.notification_jobs where profile_id='e2000000-0000-4000-8000-000000000011'),'Retry-After not respected');
select classmate.finish_notification_job(event_id,token_hash,lease_id,'invalid','UNREGISTERED') from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000012';
select pg_temp.assert_true(not exists(select 1 from classmate.device_tokens where profile_id='e2000000-0000-4000-8000-000000000012'),'Invalid token was retained');
update classmate.notices set title='/silent Changed to feed only' where id='e2000000-0000-4000-8000-000000000021';
select pg_temp.assert_true(classmate.notification_job_context(event_id,token_hash,lease_id) is null,'Silent notice could still send') from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000015';
select classmate.finish_notification_job(event_id,token_hash,lease_id,'cancelled') from claimed_jobs where profile_id in('e2000000-0000-4000-8000-000000000015','e2000000-0000-4000-8000-000000000016');
update classmate.notification_jobs set state='failed',failures=8,lease_id=null,lease_until=null where profile_id='e2000000-0000-4000-8000-000000000011';
select classmate.complete_notification_events();
select set_config('classmate.test_event',(select event_id::text from claimed_jobs where profile_id='e2000000-0000-4000-8000-000000000011'),true);
select set_config('classmate.test_hash',(select token_hash from classmate.device_tokens where profile_id='e2000000-0000-4000-8000-000000000014'),true);
set local role authenticated;
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
select classmate.report_notification_receipt(current_setting('classmate.test_event')::uuid,current_setting('classmate.test_hash'),'displayed',18);
do $$ begin
 begin perform classmate.report_notification_receipt(current_setting('classmate.test_event')::uuid,'not-my-device','displayed',18);raise exception 'Foreign device receipt accepted';exception when insufficient_privilege then null;end;
 begin perform classmate.system_health();raise exception 'CR could read private diagnostics';exception when insufficient_privilege then null;end;
 begin perform classmate.retry_failed_notifications(current_setting('classmate.test_event')::uuid);raise exception 'CR could retry private jobs';exception when insufficient_privilege then null;end;
 begin perform classmate.claim_notification_jobs(100);raise exception 'CR could claim device jobs';exception when insufficient_privilege then null;end;
end $$;
reset role;
select set_config('request.jwt.claim.sub',(select id::text from classmate.profiles where role='admin'),true);
set local role authenticated;
select pg_temp.assert_true(classmate.system_health()->>'database_bytes' is not null,'Owner diagnostics unavailable');
select pg_temp.assert_true(classmate.retry_failed_notifications(current_setting('classmate.test_event')::uuid)=1,'Owner could not retry failed device');
reset role;
-- Equal timestamp pagination must not duplicate or skip readers.
insert into auth.users(id,email,email_confirmed_at,aud,role)
 select ('e2000000-0000-4000-8000-'||lpad((1000+g)::text,12,'0'))::uuid,'receipt-fixture-'||g||'@mbstu.ac.bd',now(),'authenticated','authenticated' from generate_series(1,51) g;
insert into classmate.profiles(id,email,full_name,role,department_id,batch_id,verification_status)
 select id,email,'Receipt fixture','student','e2000000-0000-4000-8000-000000000001','e2000000-0000-4000-8000-000000000004','active' from auth.users where id::text like 'e2000000-0000-4000-8000-000000001%';
insert into classmate.notice_reads(notice_id,profile_id,read_at) select 'e2000000-0000-4000-8000-000000000021',id,now() from classmate.profiles where id::text like 'e2000000-0000-4000-8000-000000001%';
select set_config('request.jwt.claim.sub','e2000000-0000-4000-8000-000000000014',true);
set local role authenticated;
do $$ declare cursor_id uuid;cursor_time timestamptz; details jsonb;
begin
 if (select count(*) from classmate.notice_readers_page('e2000000-0000-4000-8000-000000000021'))<>50 then raise exception 'Reader page not bounded';end if;
 select profile_id,read_at into cursor_id,cursor_time from classmate.notice_readers_page('e2000000-0000-4000-8000-000000000021') order by read_at asc,profile_id desc limit 1;
 if (select count(*) from classmate.notice_readers_page('e2000000-0000-4000-8000-000000000021',cursor_time,cursor_id))<>1 then raise exception 'Reader cursor skipped or duplicated users';end if;
 details:=classmate.notice_feed_details(array['e2000000-0000-4000-8000-000000000021','e2000000-0000-4000-8000-000000000022']::uuid[]);
 if jsonb_array_length(details->'engagement')<>1 or jsonb_array_length(details->'previews')<>4 then raise exception 'Feed details scope or preview bound incorrect';end if;
 begin perform classmate.notice_readers_page('e2000000-0000-4000-8000-000000000022');raise exception 'Cross-batch readers leaked';exception when insufficient_privilege then null;end;
end $$;
reset role;
set local role anon;
do $$ begin
 begin perform classmate.report_notification_receipt(current_setting('classmate.test_event')::uuid,'not-my-device','displayed',18);raise exception 'Foreign device receipt accepted';exception when insufficient_privilege then null;end;
 begin perform classmate.system_health();raise exception 'Anonymous diagnostics leaked';exception when insufficient_privilege then null;end;
 begin perform classmate.notice_feed_details('{}');raise exception 'Anonymous feed details leaked';exception when insufficient_privilege then null;end;
end $$;
reset role;
-- Academic alerts take precedence even when a release was queued earlier.
update classmate.notification_outbox set delivered_at=now();
insert into classmate.app_releases(id,version_code,version_name)
values('e2000000-0000-4000-8000-000000000080',990001,'0.0.0');
insert into classmate.notification_outbox(kind,record_id,routing_label,created_at)
values('app_update','e2000000-0000-4000-8000-000000000080','rollback-priority-test',now()-interval '1 minute');
insert into classmate.notices(id,department_id,batch_id,author_id,title,body)
select 'e2000000-0000-4000-8000-000000000081','e2000000-0000-4000-8000-000000000001','e2000000-0000-4000-8000-000000000004',id,'Priority test','Rollback-only academic event'
from classmate.profiles where role='admin';
select classmate.prepare_notification_jobs(20);
select pg_temp.assert_true((select bool_and(kind='notice') from classmate.claim_notification_jobs(1)),'Release broadcast displaced academic alert');
rollback;
select 'Durable jobs, lease recovery, scope isolation, owner diagnostics and receipt pagination passed' as result;
