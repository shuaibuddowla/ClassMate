begin;
create function pg_temp.check(ok boolean,label text) returns void language plpgsql as $$ begin if ok is distinct from true then raise exception 'FAILED: %',label; end if;end $$;
insert into classmate.departments(id,name,code,is_active) values('b1000000-0000-4000-8000-000000000001','Blood isolated fixture','bloodfixture',true);
insert into classmate.batches(id,department_id,batch_number,academic_session) values
('b1000000-0000-4000-8000-000000000002','b1000000-0000-4000-8000-000000000001',1,1),
('b1000000-0000-4000-8000-000000000003','b1000000-0000-4000-8000-000000000001',2,2);
insert into auth.users(id,email,email_confirmed_at,aud,role) values
('b1000000-0000-4000-8000-000000000011','bloodfixturecr@mbstu.ac.bd',now(),'authenticated','authenticated'),
('b1000000-0000-4000-8000-000000000012','bloodfixturestudent@mbstu.ac.bd',now(),'authenticated','authenticated'),
('b1000000-0000-4000-8000-000000000013','bloodfixtureother@mbstu.ac.bd',now(),'authenticated','authenticated');
insert into classmate.profiles(id,email,role,department_id,batch_id,verification_status,is_cr,cr_batch_id,blood_group,mobile_number) values
('b1000000-0000-4000-8000-000000000011','bloodfixturecr@mbstu.ac.bd','student','b1000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000002','active',true,'b1000000-0000-4000-8000-000000000002','A+','+8801700000011'),
('b1000000-0000-4000-8000-000000000012','bloodfixturestudent@mbstu.ac.bd','student','b1000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000002','active',false,null,'A+','+8801700000012'),
('b1000000-0000-4000-8000-000000000013','bloodfixtureother@mbstu.ac.bd','student','b1000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000003','active',false,null,'O-','+8801700000013');
select pg_temp.check(classmate.blood_group_matches('O-','AB+',true),'O- red-cell matching');
select pg_temp.check(not classmate.blood_group_matches('O-','AB+',false),'Exact-only matching');
select pg_temp.check(not classmate.blood_group_matches('A+','O+',true),'Incompatible groups excluded');
-- Exhaustively check all 64 ABO/Rh combinations against the red-cell donor matrix.
do $$ declare recipient text; donor text; expected text[]; begin
 for recipient,expected in select * from (values
 ('O-',array['O-']),('O+',array['O-','O+']),('A-',array['O-','A-']),('A+',array['O-','O+','A-','A+']),
 ('B-',array['O-','B-']),('B+',array['O-','O+','B-','B+']),('AB-',array['O-','A-','B-','AB-']),('AB+',array['O-','O+','A-','A+','B-','B+','AB-','AB+'])) x loop
 foreach donor in array array['O-','O+','A-','A+','B-','B+','AB-','AB+'] loop
 perform pg_temp.check(classmate.blood_group_matches(donor,recipient,true)=(donor=any(expected)),'Compatibility '||donor||' -> '||recipient);
 end loop;end loop;end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000012',true);
select classmate.save_blood_preferences(true);
select classmate.create_blood_request('b1000000-0000-4000-8000-000000000021','b1000000-0000-4000-8000-000000000002','A+','Fixture hospital',2,now()+interval '1 day','+8801700000099','university',true);
-- Retry preserves identity and never sends another broadcast.
select classmate.create_blood_request('b1000000-0000-4000-8000-000000000021','b1000000-0000-4000-8000-000000000002','A+','Fixture hospital',2,now()+interval '1 day','+8801700000099','university',true);
do $$ begin
 begin perform classmate.set_blood_request_status('b1000000-0000-4000-8000-000000000021','open');raise exception 'Student approval accepted';exception when insufficient_privilege then null;end;
 begin perform classmate.create_blood_request(gen_random_uuid(),'b1000000-0000-4000-8000-000000000003','A+','Fixture hospital',1,now()+interval '1 day','+8801700000099');raise exception 'Cross-batch submission accepted';exception when insufficient_privilege then null;end;
end $$;
reset role;
select pg_temp.check(not exists(select 1 from classmate.notification_outbox where record_id='b1000000-0000-4000-8000-000000000021'),'No alert before verification');
set local role authenticated;
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000011',true);
select classmate.set_blood_request_status('b1000000-0000-4000-8000-000000000021','open');
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000013',true);
select classmate.save_blood_preferences(true);
select pg_temp.check(classmate.blood_alert_allowed('b1000000-0000-4000-8000-000000000021'),'University matching across batches');
select classmate.respond_blood_request('b1000000-0000-4000-8000-000000000021','interested');
select classmate.respond_blood_request('b1000000-0000-4000-8000-000000000021','interested');
select pg_temp.check(jsonb_array_length(classmate.blood_request_details('b1000000-0000-4000-8000-000000000021')->'volunteers')=0,'Responders cannot enumerate donor contacts');
select classmate.save_blood_preferences(true,(now() at time zone 'Asia/Dhaka')::date-119);
select pg_temp.check(not classmate.blood_alert_allowed('b1000000-0000-4000-8000-000000000021'),'119-day donation cooldown');
select classmate.save_blood_preferences(true,(now() at time zone 'Asia/Dhaka')::date-121);
select pg_temp.check(not classmate.blood_alert_allowed('b1000000-0000-4000-8000-000000000021'),'Older donation cannot erase latest donation');
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000012',true);
select pg_temp.check(jsonb_array_length(classmate.blood_request_details('b1000000-0000-4000-8000-000000000021')->'volunteers')=1,'Organizer can contact voluntary responder');
reset role;
select pg_temp.check((select count(*) from classmate.blood_responses where request_id='b1000000-0000-4000-8000-000000000021')=1,'Response idempotency');
insert into classmate.device_tokens(profile_id,fcm_token,client_platform) values('b1000000-0000-4000-8000-000000000012','classmate-load:blood:fixture:student','android');
select classmate.prepare_notification_jobs(20);
do $$ declare e uuid; h text; l uuid:=gen_random_uuid(); c jsonb; begin
 select id into e from classmate.notification_outbox where kind='blood_request' and record_id='b1000000-0000-4000-8000-000000000021';
 select token_hash into h from classmate.device_tokens where fcm_token='classmate-load:blood:fixture:student';
 update classmate.notification_jobs set state='leased',lease_id=l,lease_until=now()+interval '1 minute' where event_id=e and token_hash=h;
 c:=classmate.web_notification_job_context(e,h,l);
 perform pg_temp.check(c->>'title'='Urgent: A+ blood needed','Shared worker blood context');
 update classmate.blood_donor_preferences set opted_in=false where profile_id='b1000000-0000-4000-8000-000000000012';
 perform pg_temp.check(classmate.web_notification_job_context(e,h,l) is null,'Recheck consent on leased job');
end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000012',true);
select classmate.set_blood_request_status('b1000000-0000-4000-8000-000000000021','fulfilled');
select pg_temp.check(not classmate.blood_alert_allowed('b1000000-0000-4000-8000-000000000021'),'Fulfilled suppresses alerts');
reset role;
select pg_temp.check(not has_table_privilege('authenticated','classmate.blood_donations','SELECT'),'Private donor history');
select pg_temp.check(not has_function_privilege('anon','classmate.blood_request_feed(integer)','EXECUTE'),'Anonymous requests denied');
delete from classmate.blood_donations where profile_id='b1000000-0000-4000-8000-000000000013';
insert into classmate.blood_donations(profile_id,donated_on) values('b1000000-0000-4000-8000-000000000013',(now() at time zone 'Asia/Dhaka')::date-120);
insert into classmate.blood_requests(id,created_by,batch_id,audience,blood_group,hospital,units,needed_by,attendant_phone,compatible_donors,status,verified_by,verified_at) values
('b1000000-0000-4000-8000-000000000022','b1000000-0000-4000-8000-000000000012','b1000000-0000-4000-8000-000000000002','university','A+','Boundary hospital',1,now()+interval '1 day','+8801700000099',true,'open','b1000000-0000-4000-8000-000000000011',now());
select pg_temp.check(classmate.blood_donor_allowed('b1000000-0000-4000-8000-000000000022','b1000000-0000-4000-8000-000000000013'),'120-day boundary allowed');
update classmate.blood_requests set audience='batch' where id='b1000000-0000-4000-8000-000000000022';
select pg_temp.check(not classmate.blood_donor_allowed('b1000000-0000-4000-8000-000000000022','b1000000-0000-4000-8000-000000000013'),'Batch audience excludes other batch');
update classmate.blood_requests set audience='university',needed_by=now()-interval '1 minute' where id='b1000000-0000-4000-8000-000000000022';
select pg_temp.check(not classmate.blood_donor_allowed('b1000000-0000-4000-8000-000000000022','b1000000-0000-4000-8000-000000000013'),'Expired deadline excluded');
update classmate.blood_requests set needed_by=now()+interval '1 day' where id='b1000000-0000-4000-8000-000000000022';
update classmate.profiles set verification_status='pending' where id='b1000000-0000-4000-8000-000000000013';
select pg_temp.check(not classmate.blood_donor_allowed('b1000000-0000-4000-8000-000000000022','b1000000-0000-4000-8000-000000000013'),'Revoked membership excluded');
update classmate.profiles set cr_valid_until=now()-interval '1 minute' where id='b1000000-0000-4000-8000-000000000011';
set local role authenticated;
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000011',true);
select pg_temp.check(not classmate.blood_can_verify('b1000000-0000-4000-8000-000000000002'),'Expired CR cannot verify');
select set_config('request.jwt.claim.sub','b1000000-0000-4000-8000-000000000012',true);
select classmate.create_blood_request('b1000000-0000-4000-8000-000000000023','b1000000-0000-4000-8000-000000000002','A+','Rate hospital',1,now()+interval '1 day','+8801700000099');
do $$ begin
 begin perform classmate.create_blood_request(gen_random_uuid(),'b1000000-0000-4000-8000-000000000002','A+','Rate hospital',1,now()+interval '1 day','+8801700000099');raise sqlstate 'XX000' using message='Rate limit missing';
 exception when raise_exception then perform pg_temp.check(sqlerrm like '%at most three%','Request rate limit');end;
end $$;
rollback;
