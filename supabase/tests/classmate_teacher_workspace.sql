begin;
create function pg_temp.check(ok boolean,label text) returns void language plpgsql as $$begin if ok is distinct from true then raise exception 'FAILED: %',label;end if;end $$;
create temp table owner_fixture as select p.id from classmate.profiles p join classmate.app_owners o on o.email=p.email and o.active where p.role='admin' limit 1;
grant select on owner_fixture to authenticated;
insert into classmate.departments(id,name,code,is_active) values('b2000000-0000-4000-8000-000000000001','Isolated teacher fixture','teachfixture',true);
insert into classmate.batches(id,department_id,batch_number,academic_session) values('b2000000-0000-4000-8000-000000000002','b2000000-0000-4000-8000-000000000001',1,26),('b2000000-0000-4000-8000-000000000003','b2000000-0000-4000-8000-000000000001',2,26);
update classmate.semesters set status='active' where batch_id::text like 'b2000000-%' and semester_number=1;
insert into classmate.courses(id,department_id,course_code,course_title,course_type) values('b2000000-0000-4000-8000-000000000004','b2000000-0000-4000-8000-000000000001','TEACH1','Teacher fixture course','theory'),('b2000000-0000-4000-8000-000000000005','b2000000-0000-4000-8000-000000000001','TEACH2','Unassigned fixture course','theory');
insert into classmate.semester_courses(id,semester_id,course_id) select 'b2000000-0000-4000-8000-000000000006',id,'b2000000-0000-4000-8000-000000000004' from classmate.semesters where batch_id='b2000000-0000-4000-8000-000000000002' and semester_number=1;
insert into classmate.semester_courses(id,semester_id,course_id) select 'b2000000-0000-4000-8000-000000000007',id,'b2000000-0000-4000-8000-000000000005' from classmate.semesters where batch_id='b2000000-0000-4000-8000-000000000002' and semester_number=1;
insert into classmate.routine_slots(semester_course_id,day_of_week,start_time,end_time,room,type) values('b2000000-0000-4000-8000-000000000007',0,'10:00','10:50','Fixture room','class');
insert into auth.users(id,email,email_confirmed_at,aud,role,raw_user_meta_data) values
('b2000000-0000-4000-8000-000000000011','classmate-teacher-fixture@gmail.com',now(),'authenticated','authenticated','{"full_name":"Teacher Gmail fixture"}'),
('b2000000-0000-4000-8000-000000000012','classmate-unapproved-fixture@gmail.com',now(),'authenticated','authenticated','{}'),
('b2000000-0000-4000-8000-000000000013','ce26001@mbstu.ac.bd',now(),'authenticated','authenticated','{"full_name":"Student fixture"}');
insert into auth.identities(user_id,provider,provider_id,identity_data) select id,'google',id::text,jsonb_build_object('sub',id,'email',email,'email_verified',true) from auth.users where id::text like 'b2000000-%';
set local role authenticated;
select set_config('request.jwt.claim.sub',(select id::text from owner_fixture),true);
select classmate.owner_create_teacher('b2000000-0000-4000-8000-000000000001','Teacher Gmail fixture','classmate-teacher-fixture@gmail.com');
reset role;
update classmate.semester_courses set teacher_record_id=(select id from classmate.teacher_directory where email='classmate-teacher-fixture@gmail.com') where id='b2000000-0000-4000-8000-000000000006';
set local role authenticated;
select set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000011',true);
select pg_temp.check((classmate.create_or_initialize_profile()).role='teacher','Approved Gmail initializes teacher');
select pg_temp.check(classmate.is_active(),'Approved teacher active');
select pg_temp.check(jsonb_array_length(classmate.available_batches())=1,'Only assigned active batch listed');
select pg_temp.check((select count(*) from classmate.batch_course_catalog('b2000000-0000-4000-8000-000000000002'))=1,'Only assigned course catalog');
select pg_temp.check((select count(*) from classmate.batch_timetable_catalog('b2000000-0000-4000-8000-000000000002'))=2,'Teacher sees the whole selected batch timetable catalog');
select pg_temp.check((select count(*) from classmate.routine_slots where semester_course_id='b2000000-0000-4000-8000-000000000007')=1,'Teacher can read another teacher course period in the assigned batch');
select pg_temp.check((select role from classmate.batch_friends('b2000000-0000-4000-8000-000000000002') limit 1)='admin','Public owner identity is first in teacher directory');
select pg_temp.check(not(classmate.developer_profile() ? 'mobile_number'),'Public developer projection excludes contact data');
do $$begin
 begin perform classmate.batch_timetable_catalog('b2000000-0000-4000-8000-000000000003');raise exception 'Teacher read another batch timetable';exception when insufficient_privilege then null;end;
end $$;
select pg_temp.check(classmate.can_manage_course('b2000000-0000-4000-8000-000000000006'),'Own course tools allowed');
select pg_temp.check(not classmate.can_manage_course('b2000000-0000-4000-8000-000000000007'),'Other course changes denied');
select pg_temp.check(not classmate.can_read_batch('b2000000-0000-4000-8000-000000000003'),'Unassigned batch denied');
select pg_temp.check(not classmate.is_owner(),'Teacher never owner');
reset role;
insert into classmate.class_changes(department_id,batch_id,semester_course_id,author_id,kind,effective_date)
select 'b2000000-0000-4000-8000-000000000001','b2000000-0000-4000-8000-000000000002','b2000000-0000-4000-8000-000000000007',id,'cancelled',current_date from owner_fixture;
set local role authenticated;
select pg_temp.check((select count(*) from classmate.timetable_cancellations('b2000000-0000-4000-8000-000000000002',current_date,current_date+7))=1,'Other course cancellation marker visible within the selected batch');
do $$begin
 begin perform classmate.timetable_cancellations('b2000000-0000-4000-8000-000000000003',current_date,current_date+7);raise exception 'Other batch cancellation marker exposed';exception when insufficient_privilege then null;end;
end $$;
do $$begin
 begin perform classmate.owner_create_teacher('b2000000-0000-4000-8000-000000000001','Fake owner','fake@gmail.com');raise exception 'Teacher created another teacher';exception when insufficient_privilege then null;end;
end $$;
select set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000012',true);
do $$begin
 begin perform classmate.create_or_initialize_profile();raise exception 'Unapproved Gmail accepted';exception when invalid_authorization_specification then null;end;
end $$;
select set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000013',true);
select pg_temp.check((classmate.create_or_initialize_profile()).role='student','University student role unchanged');
reset role;
update classmate.profiles set department_id='b2000000-0000-4000-8000-000000000001',batch_id='b2000000-0000-4000-8000-000000000002',verification_status='active' where id='b2000000-0000-4000-8000-000000000013';
set local role authenticated;
select pg_temp.check(jsonb_array_length(classmate.available_batches())=1,'Student own batch only');
select pg_temp.check((select count(*) from classmate.batch_course_catalog('b2000000-0000-4000-8000-000000000002'))=2,'Student full batch catalog unchanged');
select set_config('request.jwt.claim.sub',(select id::text from owner_fixture),true);
select classmate.owner_save_teacher((select id from classmate.teacher_directory where email='classmate-teacher-fixture@gmail.com'),'Teacher Gmail fixture','classmate-teacher-replacement@gmail.com');
select set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000011',true);
select pg_temp.check(not classmate.is_active(),'Replaced email revokes old teacher');
do $$begin
 begin perform classmate.available_batches();raise exception 'Revoked teacher listed batches';exception when insufficient_privilege then null;end;
 begin perform classmate.create_or_initialize_profile();raise exception 'Revoked Gmail signed in';exception when invalid_authorization_specification then null;end;
end $$;
reset role;
select pg_temp.check(not has_function_privilege('anon','classmate.available_batches()','execute'),'Anonymous batch listing denied');
rollback;

