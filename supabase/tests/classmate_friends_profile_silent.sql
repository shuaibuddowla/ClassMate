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

do $$ declare other_id uuid:=gen_random_uuid(); b2 uuid; dept uuid;
begin
 select department_id into dept from classmate.batches where id=current_setting('classmate.qa_batch')::uuid;
 select id into b2 from classmate.batches where department_id=dept and batch_number=2;
 insert into auth.users(id,email,email_confirmed_at) values(other_id,'qa-outside-'||left(other_id::text,8)||'@mbstu.ac.bd',now());
 insert into classmate.profiles(id,email,full_name,role,department_id,batch_id,verification_status) values(other_id,'qa-outside-'||left(other_id::text,8)||'@mbstu.ac.bd','Outside batch','student',dept,b2,'active');
 perform set_config('classmate.qa_other_batch',b2::text,true);
 perform set_config('classmate.qa_other_student',other_id::text,true);
 perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_student'),true);
end $$;
set local role authenticated;
do $$ declare saved classmate.profiles%rowtype; postponed classmate.profiles%rowtype; count_members integer; details jsonb;
begin
 saved:=classmate.save_profile_details('01712345678','Tangail','O+','Mess 12');
 if saved.mobile_number<>'+8801712345678' or saved.role<>'student' or saved.batch_id<>current_setting('classmate.qa_batch')::uuid or saved.profile_completed_at is null then raise exception 'Profile save or identity preservation failed'; end if;
 begin perform classmate.save_profile_details('bad','Tangail','O+',null); raise exception 'Invalid phone accepted'; exception when invalid_parameter_value then null; end;
 begin perform classmate.save_profile_details('01712345678','Tangail','FAKE',null); raise exception 'Invalid blood group accepted'; exception when invalid_parameter_value then null; end;
 postponed:=classmate.defer_profile_completion();
 if postponed.profile_remind_after<now()+interval '23 hours' then raise exception 'Reminder not postponed'; end if;
 select count(*) into count_members from classmate.batch_friends(current_setting('classmate.qa_batch')::uuid);
 if count_members<>3 then raise exception 'Directory should contain two students and assigned teacher; got %',count_members; end if;
 select count(*) into count_members from classmate.batch_friends(current_setting('classmate.qa_batch')::uuid,'QA Student');
 if count_members<>1 then raise exception 'Search did not match actual account'; end if;
 details:=classmate.batch_friend_details(current_setting('classmate.qa_batch')::uuid,current_setting('classmate.qa_student')::uuid);
 if details->>'mobile_number'<>'+8801712345678' then raise exception 'Details missing'; end if;
 begin perform * from classmate.batch_friends(current_setting('classmate.qa_other_batch')::uuid); raise exception 'Cross batch directory leaked'; exception when insufficient_privilege then null; end;
 begin perform classmate.batch_friend_details(current_setting('classmate.qa_batch')::uuid,current_setting('classmate.qa_other_student')::uuid); raise exception 'Cross batch contact leaked'; exception when insufficient_privilege then null; end;
 if exists(select 1 from classmate.profiles where id=current_setting('classmate.qa_other_student')::uuid) then raise exception 'Raw profile RLS leaked contacts'; end if;
end $$;
reset role;
do $$ begin perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_cr'),true); end $$;
set local role authenticated;
do $$ declare quiet classmate.notices%rowtype; normal classmate.notices%rowtype;
begin
 quiet:=classmate.post_notice(current_setting('classmate.qa_batch')::uuid,null,'/silent Study update','No push please');
 if not quiet.silent or quiet.title<>'Study update' then raise exception 'Silent prefix was not processed'; end if;
 perform set_config('classmate.qa_silent_notice',quiet.id::text,true);
 quiet:=classmate.post_notice(current_setting('classmate.qa_batch')::uuid,null,'Body command','/SILENT No alert');
 if not quiet.silent or quiet.body<>'No alert' then raise exception 'Body prefix was not processed'; end if;
 perform set_config('classmate.qa_silent_body',quiet.id::text,true);
 normal:=classmate.post_notice(current_setting('classmate.qa_batch')::uuid,null,'/silentish is normal','Ordinary text');
 if normal.silent then raise exception 'Non-command prefix suppressed push'; end if;
 perform set_config('classmate.qa_normal_notice',normal.id::text,true);
end $$;
reset role;
do $$ begin
 if exists(select 1 from classmate.notification_outbox where record_id in(current_setting('classmate.qa_silent_notice')::uuid,current_setting('classmate.qa_silent_body')::uuid)) then raise exception 'Silent notice queued a push'; end if;
 if not exists(select 1 from classmate.notification_outbox where record_id=current_setting('classmate.qa_normal_notice')::uuid) then raise exception 'Normal notice push lost'; end if;
 update classmate.teacher_course_assignments set active=false where teacher_id=current_setting('classmate.qa_teacher')::uuid;
 perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_student'),true);
end $$;
set local role authenticated;
do $$ begin
 if exists(select 1 from classmate.batch_friends(current_setting('classmate.qa_batch')::uuid) where profile_id=current_setting('classmate.qa_teacher')::uuid) then raise exception 'Revoked teacher remains in directory'; end if;
end $$;
reset role;
set local role anon;
do $$ begin
 begin perform * from classmate.batch_friends(current_setting('classmate.qa_batch')::uuid); raise exception 'Anonymous directory access'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select 'Batch directory isolation, profile validation, reminders and silent push suppression passed' as result;
rollback;
