begin;

create or replace function classmate.add_batch_course(target_batch uuid, target_code text,
  target_title text, target_type text, target_credit numeric default null)
returns classmate.semester_courses language plpgsql security definer set search_path = '' as $$
declare b classmate.batches%rowtype; active_semester uuid; course classmate.courses%rowtype;
  offering classmate.semester_courses%rowtype; actor classmate.profiles%rowtype;
begin
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management permission denied' using errcode = '42501';
  end if;
  select * into b from classmate.batches where id=target_batch and is_active for update;
  if b.id is null then raise exception 'Running batch not found'; end if;
  select s.id into active_semester from classmate.semesters s
    where s.batch_id=target_batch and s.status='active';
  if active_semester is null then
    raise exception 'Publish a semester for this batch before adding courses';
  end if;
  if nullif(trim(target_code),'') is null or nullif(trim(target_title),'') is null
    or target_type not in ('theory','lab','other') then
    raise exception 'Course code, title, and type are required';
  end if;
  select * into course from classmate.courses c
    where c.department_id=b.department_id and c.course_code=upper(trim(target_code));
  if course.id is null then
    insert into classmate.courses(department_id,course_code,course_title,course_type,credit)
    values (b.department_id,upper(trim(target_code)),trim(target_title),target_type,target_credit)
    returning * into course;
  elsif course.course_title <> trim(target_title) or course.course_type <> target_type then
    raise exception 'This code already belongs to % (%)', course.course_title, course.course_type;
  end if;
  insert into classmate.semester_courses(semester_id,course_id)
    values (active_semester,course.id)
    on conflict (semester_id,course_id) do update set course_id=excluded.course_id
    returning * into offering;
  select * into actor from classmate.profiles where id=auth.uid();
  if actor.role='teacher' then
    insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
    values (actor.id,offering.id,actor.id)
    on conflict (teacher_id,semester_course_id) do update set active=true,
      assigned_by=excluded.assigned_by;
  end if;
  insert into classmate.audit_log(actor_id,action,target_id)
    values (auth.uid(),'add_batch_course',offering.id);
  return offering;
end;
$$;

commit;
