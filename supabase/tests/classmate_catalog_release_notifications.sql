begin;
do $$
declare d uuid; b uuid; s uuid; owner_id uuid; cr_id uuid:=gen_random_uuid();
  teacher_id uuid:=gen_random_uuid(); student_id uuid:=gen_random_uuid();
  base_course uuid; base_offering uuid;
begin
  select p.id into owner_id from classmate.profiles p
    join classmate.app_owners o on o.email=p.email where o.active limit 1;
  if owner_id is null then raise exception 'Owner test account is missing'; end if;
  insert into classmate.departments(name,code,is_active)
    values ('QA Batch Courses','qabcourses',true) returning id into d;
  insert into classmate.batches(department_id,batch_number,academic_session)
    values (d,1,1) returning id into b;
  select id into s from classmate.semesters where batch_id=b and semester_number=1;
  update classmate.semesters set status='active' where id=s;
  insert into auth.users(id,email,email_confirmed_at)
    values (cr_id,'qa-cr-' || left(cr_id::text,8) || '@mbstu.ac.bd',now()),
      (teacher_id,'qa-teacher-' || left(teacher_id::text,8) || '@mbstu.ac.bd',now()),
      (student_id,'qa-student-' || left(student_id::text,8) || '@mbstu.ac.bd',now());
  insert into classmate.profiles(id,email,full_name,role,department_id,batch_id,
    verification_status,is_cr,cr_batch_id)
    values (cr_id,'qa-cr-' || left(cr_id::text,8) || '@mbstu.ac.bd','QA CR',
      'student',d,b,'active',true,b);
  insert into classmate.profiles(id,email,full_name,role,department_id,verification_status)
    values (teacher_id,'qa-teacher-' || left(teacher_id::text,8) || '@mbstu.ac.bd',
      'QA Teacher','teacher',d,'active');
  insert into classmate.profiles(id,email,full_name,role,department_id,batch_id,verification_status)
    values (student_id,'qa-student-' || left(student_id::text,8) || '@mbstu.ac.bd',
      'QA Student','student',d,b,'active');
  insert into classmate.teacher_allowlist(email,department_id,active,created_by)
    values ('qa-teacher-' || left(teacher_id::text,8) || '@mbstu.ac.bd',d,true,owner_id);
  insert into classmate.courses(department_id,course_code,course_title,course_type)
    values (d,'QA000','Base Course','theory') returning id into base_course;
  insert into classmate.semester_courses(semester_id,course_id)
    values (s,base_course) returning id into base_offering;
  insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
    values (teacher_id,base_offering,owner_id);
  insert into classmate.file_metadata(department_id,batch_id,semester_course_id,
    uploaded_by,title,file_type,r2_object_key,mime_type,size_bytes,status)
    values (d,b,base_offering,owner_id,'Protected test file','pdf',
      'qa/course-file/' || gen_random_uuid(),'application/pdf',5,'pending');
  perform set_config('classmate.qa_batch',b::text,true);
  perform set_config('classmate.qa_semester',s::text,true);
  perform set_config('classmate.qa_cr',cr_id::text,true);
  perform set_config('classmate.qa_teacher',teacher_id::text,true);
  perform set_config('classmate.qa_student',student_id::text,true);
  perform set_config('classmate.qa_base_offering',base_offering::text,true);
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
end $$;

do $$ declare d uuid; c uuid; b uuid; s uuid;
begin
 select sc.course_id into c from classmate.semester_courses sc where sc.id=current_setting('classmate.qa_base_offering')::uuid;
 select department_id into d from classmate.courses where id=c;
 insert into classmate.batches(department_id,batch_number,academic_session) values(d,2,2) returning id into b;
 select id into s from classmate.semesters where batch_id=b and semester_number=1;
 insert into classmate.semester_courses(semester_id,course_id) values(s,c);
 insert into classmate.courses(department_id,course_code,course_title,course_type) values(d,'QA999','Duplicate sentinel','theory');
 perform set_config('classmate.qa_course',c::text,true);
end $$;

do $$ declare offering uuid:=current_setting('classmate.qa_base_offering')::uuid; owner_id uuid; batch uuid; dept uuid; change_id uuid;
begin
 select p.id into owner_id from classmate.profiles p join classmate.app_owners o on o.email=p.email where o.active limit 1;
 select s.batch_id,b.department_id into batch,dept from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id join classmate.batches b on b.id=s.batch_id where sc.id=offering;
 insert into classmate.routine_slots(semester_course_id,day_of_week,start_time,end_time) values(offering,1,'09:00','09:45');
 insert into classmate.class_changes(department_id,batch_id,semester_course_id,author_id,kind,effective_date) values(dept,batch,offering,owner_id,'cancelled',current_date) returning id into change_id;
 insert into classmate.notices(department_id,batch_id,semester_course_id,author_id,title,body,class_change_id) values(dept,batch,offering,owner_id,'QA cancelled','QA only',change_id);
 update classmate.file_metadata set status='active' where semester_course_id=offering;
end $$;

set local role authenticated;
do $$ declare c uuid:=current_setting('classmate.qa_course')::uuid; impact jsonb;
begin
 impact:=classmate.course_deletion_preview(c);
 if (impact->>'batches')::int<>2 or (impact->>'files')::int<>1 or (impact->>'periods')::int<>1 or (impact->>'notices')::int<>2 then raise exception 'Deletion preview mismatch'; end if;
 perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_cr'),true);
 begin perform classmate.delete_global_course(c); raise exception 'CR deleted global course'; exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ declare c uuid:=current_setting('classmate.qa_course')::uuid; owner_id uuid;
begin
 select p.id into owner_id from classmate.profiles p join classmate.app_owners o on o.email=p.email where o.active limit 1;
 perform set_config('request.jwt.claim.sub',owner_id::text,true);
end $$;
set local role authenticated;
select classmate.delete_global_course(current_setting('classmate.qa_course')::uuid);
reset role;
do $$ begin
 if exists(select 1 from classmate.courses where id=current_setting('classmate.qa_course')::uuid) then raise exception 'Global course remains'; end if;
 if exists(select 1 from classmate.semester_courses where course_id=current_setting('classmate.qa_course')::uuid) then raise exception 'Other batch offering remains'; end if;
 if exists(select 1 from classmate.routine_slots where semester_course_id=current_setting('classmate.qa_base_offering')::uuid) or exists(select 1 from classmate.class_changes where semester_course_id=current_setting('classmate.qa_base_offering')::uuid) or exists(select 1 from classmate.notices where semester_course_id=current_setting('classmate.qa_base_offering')::uuid) then raise exception 'Linked records remain'; end if;
 if not exists(select 1 from classmate.resource_cleanup_jobs where object_key like 'qa/course-file/%') then raise exception 'Protected object cleanup not queued'; end if;
 if exists(select 1 from classmate.file_metadata where semester_course_id=current_setting('classmate.qa_base_offering')::uuid) then raise exception 'File metadata remains'; end if;
end $$;
set local role service_role;
do $$ declare a uuid; b uuid;
begin
 a:=classmate.enqueue_app_release(2147483600,'999.0.1');
 b:=classmate.enqueue_app_release(2147483600,'999.0.1');
 if a<>b then raise exception 'Release duplicated'; end if;
 if not exists(select 1 from classmate.notification_outbox where id=a and kind='app_update' and batch_id is null) then raise exception 'Global event missing'; end if;
 perform set_config('classmate.qa_release_event',a::text,true);
end $$;
reset role;
do $$ declare uid uuid:=current_setting('classmate.qa_cr')::uuid; token text:='qa-release-token-'||gen_random_uuid()::text; e uuid:=current_setting('classmate.qa_release_event')::uuid;
begin
 perform set_config('request.jwt.claim.sub',uid::text,true);
 perform classmate.register_device_token(token);
 perform classmate.register_device_token(token,16);
 if not exists(select 1 from classmate.device_tokens where profile_id=uid and fcm_token=token and app_version=16) then raise exception 'Token version telemetry missing'; end if;
 if not exists(select 1 from classmate.notification_recipients(e) where profile_id=uid and fcm_token=token) then raise exception 'CR excluded from global release'; end if;
 perform set_config('request.jwt.claim.sub',uid::text,true);
 perform classmate.report_notification_receipt(e,encode(extensions.digest(token,'sha256'),'hex'),'displayed',16);
 if not exists(select 1 from classmate.notification_client_receipts where outbox_id=e and profile_id=uid) then raise exception 'Client receipt missing'; end if;
 begin perform classmate.report_notification_receipt(e,repeat('a',64),'displayed',16); raise exception 'Fake device acknowledged'; exception when insufficient_privilege then null; end;
end $$;

do $$ declare owner_id uuid; b uuid:=current_setting('classmate.qa_batch')::uuid; dept uuid;
begin
 select p.id into owner_id from classmate.profiles p join classmate.app_owners o on o.email=p.email where o.active limit 1;
 select department_id into dept from classmate.batches where id=b;
 insert into classmate.notices(department_id,batch_id,author_id,title,body) values(dept,b,owner_id,'QA priority notice','Rollback fixture');
end $$;
set local role service_role;
do $$ declare event classmate.notification_outbox%rowtype;
begin
 select * into event from classmate.claim_notification_events(1);
 if event.kind='app_update' then raise exception 'Release broadcast delayed academic notice'; end if;
end $$;
reset role;
set local role authenticated;
do $$ begin
 begin perform classmate.enqueue_app_release(2147483601,'999.0.2'); raise exception 'Student broadcast a release'; exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
