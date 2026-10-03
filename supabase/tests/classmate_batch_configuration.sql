-- Isolated fixtures; no production data is modified and all rows roll back.
begin;
create function pg_temp.assert_true(ok boolean,message text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception '%',message; end if; end $$;
select set_config('classmate.test_owner',(select id::text from classmate.profiles where role='admin' limit 1),true);
insert into classmate.departments(id,name,code,is_active) values('e3100000-0000-4000-8000-000000000001','Configuration fixture','configfixture',true);
insert into classmate.batches(id,department_id,batch_number,academic_session) values
('e3100000-0000-4000-8000-000000000002','e3100000-0000-4000-8000-000000000001',1,1),
('e3100000-0000-4000-8000-000000000003','e3100000-0000-4000-8000-000000000001',2,2),
('e3100000-0000-4000-8000-000000000004','e3100000-0000-4000-8000-000000000001',3,3);
delete from classmate.semesters where batch_id in ('e3100000-0000-4000-8000-000000000002','e3100000-0000-4000-8000-000000000003');
insert into classmate.semesters(id,batch_id,semester_number,status) values
('e3100000-0000-4000-8000-000000000005','e3100000-0000-4000-8000-000000000002',1,'active'),
('e3100000-0000-4000-8000-000000000006','e3100000-0000-4000-8000-000000000003',1,'active');
insert into auth.users(id,email,email_confirmed_at,aud,role) values
('e3100000-0000-4000-8000-000000000011','configfixturecr@mbstu.ac.bd',now(),'authenticated','authenticated'),
('e3100000-0000-4000-8000-000000000012','configfixturestudent@mbstu.ac.bd',now(),'authenticated','authenticated'),
('e3100000-0000-4000-8000-000000000013','configfixtureteacher@mbstu.ac.bd',now(),'authenticated','authenticated');
insert into classmate.profiles(id,email,role,department_id,batch_id,verification_status,is_cr,cr_batch_id) values
('e3100000-0000-4000-8000-000000000011','configfixturecr@mbstu.ac.bd','student','e3100000-0000-4000-8000-000000000001','e3100000-0000-4000-8000-000000000002','active',true,'e3100000-0000-4000-8000-000000000002'),
('e3100000-0000-4000-8000-000000000012','configfixturestudent@mbstu.ac.bd','student','e3100000-0000-4000-8000-000000000001','e3100000-0000-4000-8000-000000000003','active',false,null);
insert into classmate.courses(id,department_id,course_code,course_title,course_type) values
('e3100000-0000-4000-8000-000000000020','e3100000-0000-4000-8000-000000000001','CF101','Physics','theory');
insert into classmate.semester_courses(id,semester_id,course_id) values
('e3100000-0000-4000-8000-000000000021','e3100000-0000-4000-8000-000000000005','e3100000-0000-4000-8000-000000000020'),
('e3100000-0000-4000-8000-000000000022','e3100000-0000-4000-8000-000000000006','e3100000-0000-4000-8000-000000000020');
set local role authenticated;
select set_config('request.jwt.claim.sub','e3100000-0000-4000-8000-000000000011',true);
select classmate.save_batch_course('e3100000-0000-4000-8000-000000000002','e3100000-0000-4000-8000-000000000021','CF102','Applied Physics LAB  ','Fixture Teacher');
select pg_temp.assert_true((select course_title='Applied Physics LAB' and course_type='lab' and course_code='CF102'
  from classmate.batch_course_catalog('e3100000-0000-4000-8000-000000000002')),'CR effective fields and type detection');
select pg_temp.assert_true((select course_title='Physics' and course_code='CF101' from classmate.courses where id='e3100000-0000-4000-8000-000000000020'),'Shared course unchanged');
select pg_temp.assert_true((select teacher_name='Fixture Teacher' from classmate.timetable_details('e3100000-0000-4000-8000-000000000002',current_date,array['e3100000-0000-4000-8000-000000000021'::uuid])),'Draft teacher shown');
select classmate.save_routine_slot(null,'e3100000-0000-4000-8000-000000000021',1::smallint,'09:00','09:45','101');
select pg_temp.assert_true((select type='lab' from classmate.routine_slots where semester_course_id='e3100000-0000-4000-8000-000000000021'),'Routine uses effective course type');
do $$ begin
  begin perform classmate.save_batch_course('e3100000-0000-4000-8000-000000000003','e3100000-0000-4000-8000-000000000022','X101','Unauthorized','Teacher'); raise exception 'Cross-batch edit accepted'; exception when insufficient_privilege then null; end;
  begin perform classmate.owner_people(); raise exception 'CR owner directory accepted'; exception when insufficient_privilege then null; end;
  begin perform classmate.owner_teachers(); raise exception 'CR owner teachers accepted'; exception when insufficient_privilege then null; end;
  begin perform classmate.delete_global_course('e3100000-0000-4000-8000-000000000020'); raise exception 'CR global delete accepted'; exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub','e3100000-0000-4000-8000-000000000012',true);
select pg_temp.assert_true((select course_title='Physics' and course_type='theory' and course_code='CF101'
  from classmate.batch_course_catalog('e3100000-0000-4000-8000-000000000003')),'Other batch unchanged');
do $$ begin
  begin perform classmate.save_batch_course('e3100000-0000-4000-8000-000000000003',null,'X101','Unauthorized','Teacher'); raise exception 'Student write accepted'; exception when insufficient_privilege then null; end;
  begin perform classmate.batch_course_catalog('e3100000-0000-4000-8000-000000000002'); raise exception 'Foreign batch read accepted'; exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('classmate.test_owner'),true);
select pg_temp.assert_true(jsonb_array_length(classmate.owner_teachers())>=1,'Owner teacher list');
select pg_temp.assert_true((select count(*)=2 from classmate.owner_people('configfixture')),'Owner search finds fixtures');
select classmate.owner_save_teacher((select teacher_record_id from classmate.semester_courses where id='e3100000-0000-4000-8000-000000000021'),'Fixture Teacher','configfixtureteacher@mbstu.ac.bd');
select pg_temp.assert_true((select count(*)=0 from classmate.teacher_course_assignments where teacher_id='e3100000-0000-4000-8000-000000000013'),'No synthetic profile assigned');
reset role;
insert into classmate.profiles(id,email,full_name,role,department_id,verification_status) values
('e3100000-0000-4000-8000-000000000013','configfixtureteacher@mbstu.ac.bd','Fixture Teacher','teacher','e3100000-0000-4000-8000-000000000001','active');
select pg_temp.assert_true((select count(*)=1 from classmate.teacher_course_assignments where teacher_id='e3100000-0000-4000-8000-000000000013' and active),'Real teacher registration links assignments');
set local role authenticated;
select set_config('request.jwt.claim.sub','e3100000-0000-4000-8000-000000000011',true);
select classmate.remove_batch_course('e3100000-0000-4000-8000-000000000021');
reset role;
select pg_temp.assert_true(not exists(select 1 from classmate.routine_slots where semester_course_id='e3100000-0000-4000-8000-000000000021'),'Batch deletion removes its periods');
select pg_temp.assert_true(exists(select 1 from classmate.semester_courses where id='e3100000-0000-4000-8000-000000000022'),'Batch deletion preserves other batch');
select pg_temp.assert_true(exists(select 1 from classmate.courses where id='e3100000-0000-4000-8000-000000000020'),'Batch deletion preserves catalog');
rollback;
