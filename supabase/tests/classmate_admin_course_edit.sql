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
set local role authenticated;
do $$ declare c uuid:=current_setting('classmate.qa_course')::uuid; result jsonb;
begin
 perform classmate.save_routine_slot(null::uuid,current_setting('classmate.qa_base_offering')::uuid,0::smallint,'09:00'::time,'09:45'::time,'Room 1');
 result:=classmate.edit_course(c,' qa123 ',' Revised course name ');
 if result->'course'->>'id'<>c::text or result->'course'->>'course_code'<>'QA123' or result->'course'->>'course_title'<>'Revised course name'
   or jsonb_array_length(result->'offering_ids')<>2 then raise exception 'Course identity, normalization or shared offerings lost'; end if;

 begin perform classmate.edit_course(c,'qa999','Conflict'); raise exception 'Duplicate code accepted'; exception when unique_violation then null; end;
 begin perform classmate.edit_course(c,' ','Invalid'); raise exception 'Blank code accepted'; exception when invalid_parameter_value then null; end;
 begin perform classmate.edit_course(c,'QA123',null); raise exception 'Null title accepted'; exception when invalid_parameter_value then null; end;
 begin perform classmate.edit_course(gen_random_uuid(),'QA123','Missing'); raise exception 'Missing course accepted'; exception when invalid_parameter_value then null; end;
end $$;
do $$ declare c uuid:=current_setting('classmate.qa_course')::uuid; account_setting text;
begin
 foreach account_setting in array array['classmate.qa_cr','classmate.qa_teacher','classmate.qa_student'] loop
   perform set_config('request.jwt.claim.sub',current_setting(account_setting),true);
   begin perform classmate.edit_course(c,'QA111','Unauthorized'); raise exception 'Non-admin edited course'; exception when insufficient_privilege then null; end;
 end loop;
end $$;
reset role;
do $$ begin
 if not exists(select 1 from classmate.routine_slots where semester_course_id=current_setting('classmate.qa_base_offering')::uuid)
   or not exists(select 1 from classmate.file_metadata where semester_course_id=current_setting('classmate.qa_base_offering')::uuid)
   or not exists(select 1 from classmate.teacher_course_assignments where semester_course_id=current_setting('classmate.qa_base_offering')::uuid and active) then raise exception 'Course links lost after editing'; end if;
 if not exists(select 1 from classmate.audit_log where action='edit_course' and target_id=current_setting('classmate.qa_course')::uuid) then raise exception 'Edit audit missing'; end if;
 if (select course_title from classmate.courses where id=current_setting('classmate.qa_course')::uuid)<>'Revised course name' then raise exception 'Rejected edits changed course'; end if;
end $$;
set local role anon;
do $$ begin
 begin perform classmate.edit_course(gen_random_uuid(),'QA111','Anonymous'); raise exception 'Anonymous edited course'; exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
select 'Admin course editing, shared identity, preserved links, validation and permissions passed' as result;
