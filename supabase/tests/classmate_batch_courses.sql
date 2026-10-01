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
set local role authenticated;
do $$
declare b uuid:=current_setting('classmate.qa_batch')::uuid;
  s uuid:=current_setting('classmate.qa_semester')::uuid;
  first_id uuid; second_id uuid;
begin
  select id into first_id from classmate.add_batch_course(b,'QA101','Test Course','theory',3);
  select id into second_id from classmate.add_batch_course(b,'QA101','Test Course','theory',3);
  if first_id<>second_id or not exists (
    select 1 from classmate.semester_courses sc where sc.id=first_id and sc.semester_id=s) then
    raise exception 'Course did not appear in the active semester';
  end if;
  perform classmate.save_routine_slot(null::uuid,first_id,0::smallint,
    '09:00'::time,'09:45'::time,'Room 1');
  perform classmate.post_notice(b,first_id,'Course notice','Test');
  perform classmate.remove_batch_course(first_id);
  if exists(select 1 from classmate.semester_courses where id=first_id)
    or exists(select 1 from classmate.routine_slots where semester_course_id=first_id)
    or exists(select 1 from classmate.notices where semester_course_id=first_id)
    or exists(select 1 from classmate.notification_outbox o where o.kind='notice'
      and o.record_id in (select id from classmate.notices where semester_course_id=first_id)) then
    raise exception 'Removed course left batch content';
  end if;
  begin
    perform classmate.remove_batch_course(current_setting('classmate.qa_base_offering')::uuid);
    raise exception 'Course deletion ignored its library file';
  exception when others then
    if sqlerrm <> 'Delete this course''s library files first' then raise; end if;
  end;
end $$;
do $$
declare b uuid:=current_setting('classmate.qa_batch')::uuid;
  offering uuid;
begin
  perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_cr'),true);
  if not classmate.can_manage_batch_catalog(b) then raise exception 'CR cannot manage batch'; end if;
  select id into offering from classmate.add_batch_course(b,'QA201','CR Course','lab',1.5);
  if not classmate.can_post(b,offering) then raise exception 'CR cannot post to course'; end if;
  perform classmate.remove_batch_course(offering);

  perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_teacher'),true);
  if not classmate.can_manage_batch_catalog(b) then raise exception 'Teacher cannot manage batch'; end if;
  select id into offering from classmate.add_batch_course(b,'QA301','Teacher Course','theory',3);
  if not classmate.can_manage_course(offering) then
    raise exception 'Teacher was not assigned to new course';
  end if;
  perform classmate.remove_batch_course(offering);

  perform set_config('request.jwt.claim.sub',current_setting('classmate.qa_student'),true);
  if classmate.can_manage_batch_catalog(b) then
    raise exception 'Ordinary student received course permissions';
  end if;
  begin
    perform classmate.add_batch_course(b,'QA401','Blocked Course','theory',3);
    raise exception 'Student added a course';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
