begin;

-- ============================================================================
-- 1. Add slot-level teacher columns to routine_slots
-- ============================================================================
alter table classmate.routine_slots
  add column if not exists teacher_record_id uuid references classmate.teacher_directory(id) on delete set null,
  add column if not exists teacher_name text;

-- ============================================================================
-- 2. Create course_teachers junction table for multi-teacher support
-- ============================================================================
create table if not exists classmate.course_teachers (
  id uuid primary key default gen_random_uuid(),
  semester_course_id uuid not null references classmate.semester_courses(id) on delete cascade,
  teacher_record_id uuid not null references classmate.teacher_directory(id) on delete cascade,
  is_primary boolean not null default false,
  created_at timestamptz not null default now(),
  unique (semester_course_id, teacher_record_id)
);

create index if not exists idx_course_teachers_sc on classmate.course_teachers(semester_course_id);

-- Backfill existing teacher assignments
insert into classmate.course_teachers (semester_course_id, teacher_record_id, is_primary)
select id, teacher_record_id, true
from classmate.semester_courses
where teacher_record_id is not null
on conflict (semester_course_id, teacher_record_id) do nothing;

-- ============================================================================
-- 3. Update save_routine_slot to support slot-level teacher
-- ============================================================================
drop function if exists classmate.save_routine_slot(uuid, uuid, smallint, time without time zone, time without time zone, text);
drop function if exists classmate.save_routine_slot(uuid, uuid, smallint, time without time zone, time without time zone, text, uuid, text);

create or replace function classmate.save_routine_slot(
  target_id uuid,
  target_semester_course uuid,
  target_day smallint,
  target_start time without time zone,
  target_end time without time zone,
  target_room text,
  target_teacher_record uuid default null::uuid,
  target_teacher_name text default null::text
)
returns classmate.routine_slots
language plpgsql
security definer
set search_path to ''
as $$
declare
  slot classmate.routine_slots%rowtype;
  course_type text;
  v_teacher_name text;
begin
  if not classmate.can_manage_routine(target_semester_course) then
    raise exception 'Routine permission denied' using errcode = '42501';
  end if;

  select coalesce(sc.type_override, c.course_type) into course_type
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course and s.status in ('active', 'not_started');

  if course_type is null then
    raise exception 'Course is not available for routine editing';
  end if;

  if target_day not between 0 and 6 or target_start >= target_end then
    raise exception 'Choose a valid day and time range';
  end if;

  if target_teacher_record is not null then
    select full_name into v_teacher_name from classmate.teacher_directory where id = target_teacher_record;
    if v_teacher_name is null then
      v_teacher_name := target_teacher_name;
    end if;
  elsif target_teacher_name is not null then
    v_teacher_name := trim(target_teacher_name);
  end if;

  if target_id is null then
    insert into classmate.routine_slots(
      semester_course_id, day_of_week, start_time, end_time, room, type,
      teacher_record_id, teacher_name
    )
    values (
      target_semester_course, target_day, target_start, target_end,
      nullif(trim(target_room), ''), case when course_type in ('lab', 'practical') then 'lab' else 'class' end,
      target_teacher_record, nullif(trim(v_teacher_name), '')
    )
    returning * into slot;
  else
    if not exists (
      select 1 from classmate.routine_slots old
      where old.id = target_id and classmate.can_manage_routine(old.semester_course_id)
    ) then
      raise exception 'Routine permission denied' using errcode = '42501';
    end if;

    update classmate.routine_slots set
      semester_course_id = target_semester_course,
      day_of_week = target_day,
      start_time = target_start,
      end_time = target_end,
      room = nullif(trim(target_room), ''),
      type = case when course_type in ('lab', 'practical') then 'lab' else 'class' end,
      teacher_record_id = target_teacher_record,
      teacher_name = nullif(trim(v_teacher_name), '')
    where id = target_id
    returning * into slot;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), case when target_id is null then 'add_routine_slot' else 'edit_routine_slot' end, slot.id);

  return slot;
end;
$$;

revoke all on function classmate.save_routine_slot(uuid, uuid, smallint, time without time zone, time without time zone, text, uuid, text) from public, anon;
grant execute on function classmate.save_routine_slot(uuid, uuid, smallint, time without time zone, time without time zone, text, uuid, text) to authenticated;

-- ============================================================================
-- 4. Update batch_timetable_catalog to return assigned_teachers
-- ============================================================================
drop function if exists classmate.batch_timetable_catalog(uuid);

create function classmate.batch_timetable_catalog(target_batch uuid)
returns table(
  id uuid,
  offering_id uuid,
  course_code text,
  course_title text,
  course_type text,
  credit numeric,
  teacher_record_id uuid,
  teacher_name text,
  attendance_enabled boolean,
  assigned_teachers jsonb
)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode='42501';
  end if;

  return query select 
    c.id,
    sc.id,
    coalesce(sc.code_override, c.course_code),
    coalesce(sc.title_override, c.course_title),
    coalesce(sc.type_override, c.course_type),
    coalesce(sc.credit_override, c.credit),
    sc.teacher_record_id,
    coalesce(t.full_name, ''),
    coalesce(sc.attendance_enabled, false),
    coalesce((
      select jsonb_agg(jsonb_build_object('id', td.id, 'name', td.full_name))
      from classmate.course_teachers ct
      join classmate.teacher_directory td on td.id = ct.teacher_record_id
      where ct.semester_course_id = sc.id
    ), case when t.id is not null then jsonb_build_array(jsonb_build_object('id', t.id, 'name', t.full_name)) else '[]'::jsonb end)
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  left join classmate.teacher_directory t on t.id = sc.teacher_record_id
  where s.batch_id = target_batch and s.status = 'active'
  order by coalesce(sc.code_override, c.course_code), sc.id;
end;
$$;

revoke all on function classmate.batch_timetable_catalog(uuid) from public, anon;
grant execute on function classmate.batch_timetable_catalog(uuid) to authenticated;

-- ============================================================================
-- 5. Update batch_course_catalog to return assigned_teachers & co-teachers string
-- ============================================================================
drop function if exists classmate.batch_course_catalog(uuid);

create function classmate.batch_course_catalog(target_batch uuid)
returns table(
  id uuid,
  offering_id uuid,
  course_code text,
  course_title text,
  course_type text,
  credit numeric,
  teacher_record_id uuid,
  teacher_name text,
  assigned_teachers jsonb
)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode='42501';
  end if;

  return query select
    c.id,
    sc.id,
    coalesce(sc.code_override, c.course_code),
    coalesce(sc.title_override, c.course_title),
    coalesce(sc.type_override, c.course_type),
    coalesce(sc.credit_override, c.credit),
    sc.teacher_record_id,
    coalesce((
      select string_agg(td.full_name, ' · ' order by ct.is_primary desc, td.full_name)
      from classmate.course_teachers ct
      join classmate.teacher_directory td on td.id = ct.teacher_record_id
      where ct.semester_course_id = sc.id
    ), t.full_name, ''),
    coalesce((
      select jsonb_agg(jsonb_build_object('id', td.id, 'name', td.full_name))
      from classmate.course_teachers ct
      join classmate.teacher_directory td on td.id = ct.teacher_record_id
      where ct.semester_course_id = sc.id
    ), case when t.id is not null then jsonb_build_array(jsonb_build_object('id', t.id, 'name', t.full_name)) else '[]'::jsonb end)
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  left join classmate.teacher_directory t on t.id = sc.teacher_record_id
  where s.batch_id = target_batch and s.status = 'active'
    and (not exists(select 1 from classmate.profiles p where p.id = auth.uid() and p.role = 'teacher')
      or exists(select 1 from classmate.teacher_course_assignments a where a.teacher_id = auth.uid() and a.active and a.semester_course_id = sc.id))
  order by coalesce(sc.code_override, c.course_code), sc.id;
end;
$$;

revoke all on function classmate.batch_course_catalog(uuid) from public, anon;
grant execute on function classmate.batch_course_catalog(uuid) to authenticated;

-- ============================================================================
-- 6. Update save_batch_course to handle multi-teachers & practical/lab type
-- ============================================================================
create or replace function classmate.save_batch_course(
  target_batch uuid,
  target_offering uuid,
  target_code text,
  target_title text,
  target_teacher_name text,
  target_teacher_record uuid default null::uuid,
  target_credit numeric default null::numeric,
  target_catalog_course uuid default null::uuid,
  target_teacher_records uuid[] default null::uuid[],
  target_course_type text default null::text
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  sem uuid;
  dept uuid;
  offering classmate.semester_courses%rowtype;
  catalog_id uuid;
  primary_teacher_id uuid := target_teacher_record;
  inferred_type text;
  code_value text := upper(trim(target_code));
  rec_id uuid;
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

  if target_course_type is not null and lower(trim(target_course_type)) in ('lab', 'practical') then
    inferred_type := 'lab';
  elsif target_course_type is not null and lower(trim(target_course_type)) in ('theory', 'regular', 'class') then
    inferred_type := 'theory';
  else
    inferred_type := case when trim(target_title) ~* '(lab|labs|practical|practicals)$' then 'lab' else 'theory' end;
  end if;

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

  -- Determine primary teacher ID
  if target_teacher_records is not null and array_length(target_teacher_records, 1) > 0 then
    primary_teacher_id := target_teacher_records[1];
  elsif primary_teacher_id is not null then
    -- keep primary_teacher_id
  elsif nullif(trim(target_teacher_name), '') is not null then
    if length(trim(target_teacher_name)) not between 2 and 200 then
      raise exception 'Enter a valid teacher name';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(dept::text || lower(regexp_replace(trim(target_teacher_name), '\s+', ' ', 'g')), 0));
    select id into primary_teacher_id from classmate.teacher_directory
      where department_id = dept and lower(regexp_replace(trim(full_name), '\s+', ' ', 'g')) =
        lower(regexp_replace(trim(target_teacher_name), '\s+', ' ', 'g'))
      order by (profile_id is not null) desc, (email is not null) desc, created_at, id limit 1;
    if primary_teacher_id is null then
      insert into classmate.teacher_directory(department_id, full_name, created_by)
        values(dept, trim(target_teacher_name), auth.uid()) returning id into primary_teacher_id;
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
    teacher_record_id = primary_teacher_id from classmate.courses c where sc.id = offering.id and c.id = sc.course_id;

  -- Manage course_teachers junction table
  delete from classmate.course_teachers where semester_course_id = offering.id;
  if target_teacher_records is not null and array_length(target_teacher_records, 1) > 0 then
    foreach rec_id in array target_teacher_records loop
      insert into classmate.course_teachers(semester_course_id, teacher_record_id, is_primary)
      values(offering.id, rec_id, (rec_id = primary_teacher_id))
      on conflict (semester_course_id, teacher_record_id) do nothing;
    end loop;
  elsif primary_teacher_id is not null then
    insert into classmate.course_teachers(semester_course_id, teacher_record_id, is_primary)
    values(offering.id, primary_teacher_id, true)
    on conflict (semester_course_id, teacher_record_id) do nothing;
  end if;

  -- Auto-assign active teacher assignment if teacher has a registered profile
  if primary_teacher_id is not null then
    insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
    select td.profile_id, offering.id, auth.uid()
    from classmate.teacher_directory td
    where td.id = primary_teacher_id and td.profile_id is not null
    on conflict (teacher_id, semester_course_id) do update set active = true;
  end if;

  update classmate.routine_slots set type = case when inferred_type in ('lab', 'practical') then 'lab' else 'class' end
    where semester_course_id = offering.id;

  insert into classmate.audit_log(actor_id, action, target_id)
    values(auth.uid(), 'save_batch_course', offering.id);

  return jsonb_build_object('id', offering.id, 'course_id', offering.course_id);
end;
$$;

revoke all on function classmate.save_batch_course(uuid, uuid, text, text, text, uuid, numeric, uuid, uuid[], text) from public, anon;
grant execute on function classmate.save_batch_course(uuid, uuid, text, text, text, uuid, numeric, uuid, uuid[], text) to authenticated;

notify pgrst, 'reload schema';

commit;
