begin;

create function classmate.can_manage_batch_catalog(target_batch uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p
    where p.id = (select auth.uid()) and p.verification_status = 'active'
      and ((p.role = 'student' and p.is_cr and p.batch_id = target_batch
        and p.cr_batch_id = target_batch
        and (p.cr_valid_until is null or p.cr_valid_until > now()))
      or (p.role = 'teacher' and exists (
        select 1 from classmate.teacher_allowlist a
        join classmate.batches b on b.department_id = a.department_id
        where a.email = p.email and a.active and b.id = target_batch
          and classmate.can_read_batch(target_batch)))))
$$;
revoke all on function classmate.can_manage_batch_catalog(uuid) from public, anon;
grant execute on function classmate.can_manage_batch_catalog(uuid) to authenticated;

-- CRs can manage course-scoped academic content in their own active batch.
create or replace function classmate.can_post(target_batch uuid,
  target_course uuid default null)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id=(select auth.uid())
      and p.verification_status='active' and (
        (p.role='student' and p.is_cr and p.cr_batch_id=target_batch
          and p.batch_id=target_batch
          and (p.cr_valid_until is null or p.cr_valid_until>now())
          and (target_course is null or exists (
            select 1 from classmate.semester_courses sc
            join classmate.semesters s on s.id=sc.semester_id
            where sc.id=target_course and s.batch_id=target_batch and s.status='active')))
        or (target_course is not null and classmate.can_manage_course(target_course)
          and exists (select 1 from classmate.semester_courses sc
            join classmate.semesters s on s.id=sc.semester_id
            where sc.id=target_course and s.batch_id=target_batch and s.status='active'))));
$$;

create function classmate.add_batch_course(target_batch uuid, target_code text,
  target_title text, target_type text, target_credit numeric default null)
returns classmate.semester_courses language plpgsql security definer set search_path = '' as $$
declare b classmate.batches%rowtype; semester_id uuid; course classmate.courses%rowtype;
  offering classmate.semester_courses%rowtype; actor classmate.profiles%rowtype;
begin
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management permission denied' using errcode = '42501';
  end if;
  select * into b from classmate.batches where id=target_batch and is_active for update;
  if b.id is null then raise exception 'Running batch not found'; end if;
  select s.id into semester_id from classmate.semesters s
    where s.batch_id=target_batch and s.status='active';
  if semester_id is null then
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
    values (semester_id,course.id)
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
revoke all on function classmate.add_batch_course(uuid,text,text,text,numeric)
  from public, anon;
grant execute on function classmate.add_batch_course(uuid,text,text,text,numeric)
  to authenticated;

create function classmate.remove_batch_course(target_offering uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare offering classmate.semester_courses%rowtype; target_batch uuid;
begin
  select * into offering from classmate.semester_courses where id=target_offering for update;
  if offering.id is null then raise exception 'Course not found'; end if;
  select s.batch_id into target_batch from classmate.semesters s
    where s.id=offering.semester_id and s.status='active';
  if target_batch is null then raise exception 'Only active batch courses can be removed'; end if;
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management permission denied' using errcode = '42501';
  end if;
  if exists (select 1 from classmate.file_metadata where semester_course_id=target_offering) then
    raise exception 'Delete this course''s library files first';
  end if;
  delete from classmate.notification_outbox o using classmate.notices n
    where n.semester_course_id=target_offering and o.kind='notice' and o.record_id=n.id;
  delete from classmate.notices where semester_course_id=target_offering;
  delete from classmate.notification_outbox o using classmate.class_changes c
    where c.semester_course_id=target_offering and o.kind='class_change' and o.record_id=c.id;
  delete from classmate.class_changes where semester_course_id=target_offering;
  delete from classmate.routine_slots where semester_course_id=target_offering;
  delete from classmate.teacher_course_assignments where semester_course_id=target_offering;
  delete from classmate.semester_courses where id=target_offering;
  insert into classmate.audit_log(actor_id,action,target_id)
    values (auth.uid(),'remove_batch_course',target_offering);
end;
$$;
revoke all on function classmate.remove_batch_course(uuid) from public, anon;
grant execute on function classmate.remove_batch_course(uuid) to authenticated;

commit;
