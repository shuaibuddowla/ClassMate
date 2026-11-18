begin;

-- Auto-assign verified teacher to course assignment on save_batch_course
create or replace function classmate.save_batch_course(
  target_batch uuid,
  target_offering uuid,
  target_code text,
  target_title text,
  target_teacher_name text,
  target_teacher_record uuid default null::uuid,
  target_credit numeric default null::numeric,
  target_catalog_course uuid default null::uuid
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
  sem uuid;
  dept uuid;
  offering classmate.semester_courses%rowtype;
  catalog_id uuid;
  teacher_id uuid;
  inferred_type text;
  code_value text := upper(trim(target_code));
begin
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management denied' using errcode='42501';
  end if;

  select b.department_id, s.id into dept, sem
  from classmate.batches b
  join classmate.semesters s on s.batch_id = b.id and s.status = 'active'
  where b.id = target_batch and b.is_active for update of s;

  if sem is null then
    raise exception 'Ask your administrator to publish an active semester first';
  end if;

  if length(code_value) not between 2 and 30 or code_value is null
    or length(trim(target_title)) not between 2 and 200 or target_title is null then
    raise exception 'Enter a course code and name';
  end if;

  if target_credit is not null and (target_credit <= 0 or target_credit > 30) then
    raise exception 'Credit must be between 0 and 30';
  end if;

  inferred_type := case when trim(target_title) ~* 'lab$' then 'lab' else 'theory' end;

  if target_offering is not null then
    if not classmate.is_owner() and exists(select 1 from classmate.profiles where id = auth.uid() and role = 'teacher') then
      raise exception 'Only the admin or batch CR can edit course details' using errcode='42501';
    end if;
    select * into offering from classmate.semester_courses where id = target_offering and semester_id = sem for update;
    if not found then
      raise exception 'Course does not belong to this active batch' using errcode='42501';
    end if;
  end if;

  if exists(
    select 1 from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    where sc.semester_id = sem and sc.id is distinct from target_offering
      and upper(coalesce(sc.code_override, c.course_code)) = code_value
  ) then
    raise exception 'This course code is already in the batch';
  end if;

  if target_teacher_record is not null then
    select id into teacher_id from classmate.teacher_directory where id = target_teacher_record;
    if teacher_id is null then
      raise exception 'Teacher record not found in directory' using errcode='42501';
    end if;
  elsif nullif(trim(target_teacher_name), '') is not null then
    if length(trim(target_teacher_name)) not between 2 and 200 then
      raise exception 'Enter a valid teacher name';
    end if;
    -- Serialize matching names within a department, including concurrent course saves.
    perform pg_advisory_xact_lock(hashtextextended(dept::text || lower(regexp_replace(trim(target_teacher_name), '\s+', ' ', 'g')), 0));
    select id into teacher_id from classmate.teacher_directory
      where department_id = dept and lower(regexp_replace(trim(full_name), '\s+', ' ', 'g')) =
        lower(regexp_replace(trim(target_teacher_name), '\s+', ' ', 'g'))
      order by (profile_id is not null) desc, (email is not null) desc, created_at, id limit 1;
    if teacher_id is null then
      insert into classmate.teacher_directory(department_id, full_name, created_by)
        values(dept, trim(target_teacher_name), auth.uid()) returning id into teacher_id;
    end if;
  end if;

  if target_offering is null then
    if target_catalog_course is not null then
      select id into catalog_id from classmate.courses where id = target_catalog_course and department_id = dept;
      if catalog_id is null then raise exception 'Course belongs to a different department'; end if;
    else
      insert into classmate.courses(department_id, course_code, course_title, course_type, credit)
        values(dept, code_value, trim(target_title), inferred_type, target_credit)
        on conflict(department_id, course_code) do nothing;
      select id into catalog_id from classmate.courses where department_id = dept and course_code = code_value;
    end if;
    if exists(select 1 from classmate.semester_courses where semester_id = sem and course_id = catalog_id) then
      raise exception 'This shared course is already in the batch'; end if;
    insert into classmate.semester_courses(semester_id, course_id) values(sem, catalog_id) returning * into offering;
  end if;

  update classmate.semester_courses sc set
    code_override = case when code_value = c.course_code then null else code_value end,
    title_override = case when trim(target_title) = c.course_title then null else trim(target_title) end,
    type_override = case when inferred_type = c.course_type then null else inferred_type end,
    credit_override = case when target_credit is not distinct from c.credit then null else target_credit end,
    teacher_record_id = teacher_id from classmate.courses c where sc.id = offering.id and c.id = sc.course_id;

  -- Auto-assign active teacher assignment if teacher has a registered profile
  if teacher_id is not null then
    insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
    select td.profile_id, offering.id, auth.uid()
    from classmate.teacher_directory td
    where td.id = teacher_id and td.profile_id is not null
    on conflict (teacher_id, semester_course_id) do update set active = true;
  end if;

  update classmate.routine_slots set type = case when inferred_type = 'lab' then 'lab' else 'class' end
    where semester_course_id = offering.id;

  insert into classmate.audit_log(actor_id, action, target_id)
    values(auth.uid(), 'save_batch_course', offering.id);

  return jsonb_build_object('id', offering.id, 'course_id', offering.course_id);
end $function$;

notify pgrst, 'reload schema';
commit;
