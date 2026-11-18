-- 202611090003_fix_isolation_can_post_and_notifications.sql
-- Fixes university, batch, and department isolation, notice posting permissions,
-- course file uploads, multi-teacher assignment visibility, and multi-tenant notification delivery.

begin;

-- ============================================================================
-- 1. classmate.can_manage_course: Support multi-teacher junction & admins
-- ============================================================================
create or replace function classmate.can_manage_course(target_semester_course uuid)
returns boolean
language plpgsql
stable security definer
set search_path to ''
as $$
begin
  -- App owner
  if classmate.is_owner() then
    return true;
  end if;

  -- Admin of the institution
  if exists (
    select 1 from classmate.profiles p
    join classmate.semester_courses sc on sc.id = target_semester_course
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id
    join classmate.departments d on d.id = b.department_id
    where p.id = (select auth.uid())
      and p.role = 'admin'
      and p.verification_status = 'active'
      and (p.institution_id is null or p.institution_id = d.institution_id)
  ) then
    return true;
  end if;

  -- Teacher assigned to this course (via course_teachers, legacy teacher_course_assignments, or semester_courses)
  return exists (
    select 1
    from classmate.profiles p
    join classmate.semester_courses sc on sc.id = target_semester_course
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id
    join classmate.departments d on d.id = b.department_id
    where p.id = (select auth.uid())
      and p.role = 'teacher'
      and p.verification_status = 'active'
      and (
        -- Check course_teachers junction table (multi-teacher system)
        exists (
          select 1 from classmate.course_teachers ct
          join classmate.teacher_directory td on td.id = ct.teacher_record_id
          where ct.semester_course_id = target_semester_course
            and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
        )
        or
        -- Check legacy teacher_course_assignments
        exists (
          select 1 from classmate.teacher_course_assignments a
          where a.teacher_id = p.id
            and a.semester_course_id = target_semester_course
            and a.active
        )
        or
        -- Check direct teacher_record_id on semester_courses
        exists (
          select 1 from classmate.teacher_directory td
          where td.id = sc.teacher_record_id
            and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
        )
      )
  );
end;
$$;

-- ============================================================================
-- 2. classmate.can_read_batch: Allow admins, enrolled students, and assigned/dept teachers
-- ============================================================================
create or replace function classmate.can_read_batch(target_batch uuid)
returns boolean
language plpgsql
stable security definer
set search_path to ''
as $$
begin
  if classmate.is_owner() then
    return true;
  end if;

  return exists (
    select 1 from classmate.profiles p
    join classmate.batches b on b.id = target_batch
    join classmate.departments d on d.id = b.department_id
    where p.id = (select auth.uid())
      and p.verification_status = 'active'
      and (
        -- Admin of the institution
        (p.role = 'admin' and (p.institution_id is null or p.institution_id = d.institution_id))
        or
        -- Student of the batch (or overridden effective batch)
        (p.role = 'student' and (p.batch_id = target_batch or p.effective_batch_id = target_batch))
        or
        -- Teacher of the department or assigned to any course of the batch
        (p.role = 'teacher' and (
          p.department_id = b.department_id
          or exists (
            select 1 from classmate.semesters s
            join classmate.semester_courses sc on sc.semester_id = s.id
            where s.batch_id = target_batch
              and (
                exists (
                  select 1 from classmate.course_teachers ct
                  join classmate.teacher_directory td on td.id = ct.teacher_record_id
                  where ct.semester_course_id = sc.id
                    and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
                )
                or exists (
                  select 1 from classmate.teacher_course_assignments a
                  where a.teacher_id = p.id and a.semester_course_id = sc.id and a.active
                )
              )
          )
        ))
      )
  );
end;
$$;

-- ============================================================================
-- 3. classmate.can_manage_batch: Allow admins and active CRs
-- ============================================================================
create or replace function classmate.can_manage_batch(target_batch uuid)
returns boolean
language sql
stable security definer
set search_path to ''
as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p
    join classmate.batches b on b.id = target_batch
    join classmate.departments d on d.id = b.department_id
    where p.id = (select auth.uid()) and p.verification_status = 'active'
      and (
        (p.role = 'student' and p.is_cr
          and (p.cr_batch_id = target_batch or p.batch_id = target_batch)
          and (p.cr_valid_until is null or p.cr_valid_until > now()))
        or
        (p.role = 'admin' and (p.institution_id is null or p.institution_id = d.institution_id))
      )
  );
$$;

-- ============================================================================
-- 4. classmate.can_manage_routine: Allow owner, course managers, admins, and active CRs
-- ============================================================================
create or replace function classmate.can_manage_routine(target_course uuid)
returns boolean
language sql
stable security definer
set search_path to ''
as $$
  select classmate.is_owner() or classmate.can_manage_course(target_course) or exists (
    select 1 from classmate.profiles p
    join classmate.semester_courses sc on sc.id = target_course
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id
    join classmate.departments d on d.id = b.department_id
    where p.id = (select auth.uid())
      and p.verification_status = 'active'
      and (
        (p.role = 'admin' and (p.institution_id is null or p.institution_id = d.institution_id))
        or
        (p.role = 'student' and p.is_cr
          and (p.cr_batch_id = s.batch_id or p.batch_id = s.batch_id)
          and (p.cr_valid_until is null or p.cr_valid_until > now()))
      )
  );
$$;

-- ============================================================================
-- 5. classmate.can_post: Fix notice & resource posting permissions
-- ============================================================================
create or replace function classmate.can_post(target_batch uuid, target_course uuid default null::uuid)
returns boolean
language plpgsql
stable security definer
set search_path to ''
as $$
declare
  caller_id uuid := auth.uid();
  p classmate.profiles%rowtype;
begin
  if classmate.is_owner() then
    return true;
  end if;

  select * into p from classmate.profiles where id = caller_id and verification_status = 'active';
  if p.id is null then
    return false;
  end if;

  -- Admins can post to batches in their institution
  if p.role = 'admin' then
    return exists (
      select 1 from classmate.batches b
      join classmate.departments d on d.id = b.department_id
      where b.id = target_batch and b.is_active
        and (p.institution_id is null or p.institution_id = d.institution_id)
        and (
          target_course is null
          or exists (
            select 1 from classmate.semester_courses sc
            join classmate.semesters s on s.id = sc.semester_id
            where sc.id = target_course and s.batch_id = target_batch and s.status = 'active'
          )
        )
    );
  end if;

  -- Active CR of this batch
  if p.role = 'student' and p.is_cr
     and (p.cr_batch_id = target_batch or p.batch_id = target_batch)
     and (p.cr_valid_until is null or p.cr_valid_until > now()) then
    return exists (
      select 1 from classmate.batches b where b.id = target_batch and b.is_active
    ) and (
      target_course is null
      or exists (
        select 1 from classmate.semester_courses sc
        join classmate.semesters s on s.id = sc.semester_id
        where sc.id = target_course and s.batch_id = target_batch and s.status = 'active'
      )
    );
  end if;

  -- Teachers:
  -- If course is specified, must be able to manage that course
  -- If course is null, must be a teacher in the same department as the batch
  if p.role = 'teacher' then
    if target_course is not null then
      return classmate.can_manage_course(target_course)
        and exists (
          select 1 from classmate.semester_courses sc
          join classmate.semesters s on s.id = sc.semester_id
          where sc.id = target_course and s.batch_id = target_batch and s.status = 'active'
        );
    else
      return exists (
        select 1 from classmate.batches b
        where b.id = target_batch and b.is_active and b.department_id = p.department_id
      );
    end if;
  end if;

  return false;
end;
$$;

-- ============================================================================
-- 6. classmate.routing_label: Multi-tenant slug prefix isolation
-- ============================================================================
create or replace function classmate.routing_label(target_batch uuid, target_course uuid default null::uuid)
returns text
language plpgsql
stable security definer
set search_path to ''
as $$
declare
  label text;
begin
  select coalesce(i.slug, 'mbstu') || '_' || d.code || '_batch_' || b.batch_number into label
  from classmate.batches b
  join classmate.departments d on d.id = b.department_id
  left join classmate.institutions i on i.id = d.institution_id
  where b.id = target_batch;

  if label is null then raise exception 'Batch not found'; end if;

  if target_course is not null then
    select label || '_course_' || regexp_replace(lower(c.course_code), '[^a-z0-9]', '_', 'g') into label
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_course and s.batch_id = target_batch;
    if label is null then raise exception 'Course scope mismatch'; end if;
  end if;

  return label;
end;
$$;

-- ============================================================================
-- 7. classmate.notification_recipients: Multi-tenant batch broadcast & teacher notification
-- ============================================================================
create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language plpgsql
security definer
set search_path to ''
as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return; end if;

  -- Case A: App update announcement -> Deliver to all active registered users across all universities!
  if e.kind = 'app_update' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.verification_status = 'active';
    return;
  end if;

  -- Case B: Targeted personal event (student ban, ban lifted, direct alert)
  if e.recipient_profile_id is not null then
    if e.kind in ('student_ban', 'student_ban_lifted') then
      return query
        select distinct p.id, dt.fcm_token
        from classmate.profiles p
        join classmate.device_tokens dt on dt.profile_id = p.id
        where p.id = e.recipient_profile_id and p.verification_status = 'active';
      return;
    end if;

    -- For any other targeted personal event, do NOT deliver if the student is currently banned
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        );
      return;
  end if;

  -- Case C: Batch / academic event broadcast (notices, routine changes, class cancellations, resources)
  -- Strictly ISOLATED to the specific batch & department!
  -- Strictly EXCLUDES any student who currently has an active ban!
  return query
    select distinct p.id, dt.fcm_token
    from classmate.profiles p
    join classmate.device_tokens dt on dt.profile_id = p.id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where (
      (
        p.role in ('student', 'admin')
        and p.batch_id = b.id
        and p.department_id = b.department_id
        and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        )
      )
      or (p.role = 'teacher' and p.verification_status = 'active' and (
        -- Course-specific event: notify teachers assigned to that course
        (e.semester_course_id is not null and (
          exists (
            select 1 from classmate.course_teachers ct
            join classmate.teacher_directory td on td.id = ct.teacher_record_id
            where ct.semester_course_id = e.semester_course_id
              and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
          )
          or exists (
            select 1 from classmate.teacher_course_assignments a
            where a.teacher_id = p.id and a.semester_course_id = e.semester_course_id and a.active
          )
        ))
        -- General batch notice: notify teachers of the same department
        or (e.semester_course_id is null and p.department_id = b.department_id)
      ))
    );
end;
$$;

-- ============================================================================
-- 8. classmate.batch_course_catalog: Support multi-teacher junction in course catalog
-- ============================================================================
create or replace function classmate.batch_course_catalog(target_batch uuid)
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
language plpgsql
stable security definer
set search_path to ''
as $$
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
      select string_agg(td.full_name, ' • ' order by ct.is_primary desc, td.full_name)
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
    and (
      not exists(select 1 from classmate.profiles p where p.id = auth.uid() and p.role = 'teacher')
      or exists(
        select 1 from classmate.course_teachers ct
        join classmate.teacher_directory td on td.id = ct.teacher_record_id
        join classmate.profiles p on p.id = auth.uid()
        where ct.semester_course_id = sc.id
          and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
      )
      or exists(
        select 1 from classmate.teacher_course_assignments a
        where a.teacher_id = auth.uid() and a.active and a.semester_course_id = sc.id
      )
    )
  order by coalesce(sc.code_override, c.course_code), sc.id;
end;
$$;

-- ============================================================================
-- 9. classmate.save_batch_course: Auto-activate first semester if not active
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
  left join classmate.semesters s on s.batch_id = b.id and s.status = 'active'
  where b.id = target_batch and b.is_active;

  if dept is null then
    raise exception 'Batch not found or inactive';
  end if;

  -- If no active semester exists, automatically activate the lowest not_started semester (or semester 1)
  if sem is null then
    select s.id into sem
    from classmate.semesters s
    where s.batch_id = target_batch and s.status in ('active', 'not_started')
    order by (s.status = 'active') desc, s.semester_number asc
    limit 1;

    if sem is not null then
      update classmate.semesters set status = 'active', published_at = coalesce(published_at, now())
      where id = sem;
    else
      insert into classmate.semesters (batch_id, semester_number, status, published_at)
      values (target_batch, 1, 'active', now())
      returning id into sem;
    end if;
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

-- ============================================================================
-- 10. classmate.edit_resource_metadata: Allow CRs, admins, uploader, and course teachers
-- ============================================================================
create or replace function classmate.edit_resource_metadata(
  target_resource uuid,
  target_title text,
  target_category text default 'notes'::text,
  target_course uuid default null::uuid,
  target_link_url text default null::text
)
returns classmate.file_metadata
language plpgsql
security definer
set search_path to ''
as $$
declare
  resource classmate.file_metadata;
  updated classmate.file_metadata;
  v_url text;
  v_cat text;
begin
  select * into resource from classmate.file_metadata where id = target_resource and status = 'active' for update;
  if not found then raise exception 'File is no longer available' using errcode = 'P0002'; end if;

  if not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      where p.id = (select auth.uid())
        and p.verification_status = 'active'
        and (
          (p.role = 'admin')
          or
          (p.role = 'student' and p.is_cr
           and (p.cr_batch_id = resource.batch_id or p.batch_id = resource.batch_id)
           and (p.cr_valid_until is null or p.cr_valid_until > now()))
          or
          (p.role = 'teacher' and (
            p.id = resource.uploaded_by
            or (resource.semester_course_id is not null and classmate.can_manage_course(resource.semester_course_id))
          ))
          or
          (resource.uploaded_by = p.id)
        )
    )
  ) then
    raise exception 'Only the owner, an admin, an active CR, or the uploader can edit files' using errcode = '42501';
  end if;

  if target_title is null or length(trim(target_title)) not between 1 and 200 then
    raise exception 'File name must contain 1 to 200 characters' using errcode = '23514';
  end if;

  v_cat := coalesce(nullif(trim(target_category), ''), coalesce(resource.category, 'notes'));
  if v_cat not in ('notes', 'slides', 'questions', 'syllabus', 'other') then
    v_cat := 'notes';
  end if;

  if target_course is null or not exists (
    select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id
    where sc.id = target_course and s.batch_id = resource.batch_id and b.department_id = resource.department_id
      and (s.status = 'active' or sc.id = resource.semester_course_id)
  ) then
    raise exception 'Choose a subject in this batch' using errcode = '23514';
  end if;

  if target_link_url is not null and trim(target_link_url) <> '' then
    v_url := trim(target_link_url);
    if not (v_url ~* '^https?://') then
      v_url := 'https://' || v_url;
    end if;
  else
    v_url := resource.link_url;
  end if;

  update classmate.file_metadata set
    title = trim(target_title),
    category = v_cat,
    semester_course_id = target_course,
    link_url = v_url
  where id = resource.id returning * into updated;

  update classmate.notices set
    title = left('Resource: ' || updated.title, 200),
    semester_course_id = updated.semester_course_id
  where resource_id = resource.id;

  insert into classmate.audit_log (actor_id, action, target_id)
  values ((select auth.uid()), 'edit_resource_metadata', resource.id);

  return updated;
end;
$$;

-- ============================================================================
-- 11. public.publish_batch_notice: Multi-tenant batch resolution fallback
-- ============================================================================
create or replace function public.publish_batch_notice(
  target_batch_code text,
  notice_title text,
  notice_body text,
  notice_priority text default 'normal'::text
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  caller_profile_id uuid := auth.uid();
  resolved_classmate_batch uuid;
  resolved_public_batch uuid;
  created_notice classmate.notices;
  public_notice_id uuid;
begin
  if caller_profile_id is null then
    raise exception 'An active account is required' using errcode = '42501';
  end if;

  -- 1. Try resolving in modern classmate.batches (by UUID or dept_code + batch_number)
  if target_batch_code ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select b.id into resolved_classmate_batch from classmate.batches b where b.id = target_batch_code::uuid and b.is_active;
  else
    select b.id into resolved_classmate_batch
    from classmate.batches b
    join classmate.departments d on d.id = b.department_id
    where regexp_replace(lower(d.code || b.batch_number::text), '[^a-z0-9]', '', 'g')
        = regexp_replace(lower(target_batch_code), '[^a-z0-9]', '', 'g')
      and b.is_active
    limit 1;
  end if;

  if resolved_classmate_batch is not null then
    created_notice := classmate.post_notice(resolved_classmate_batch, null, notice_title, notice_body);
    return jsonb_build_object('id', created_notice.id);
  end if;

  -- 2. Fallback to legacy public.batches if exists
  select b.id into resolved_public_batch
  from public.batches b
  join public.departments d on d.id = b.department_id
  where regexp_replace(lower(d.code || b.cohort_code), '[^a-z0-9]', '', 'g')
      = regexp_replace(lower(target_batch_code), '[^a-z0-9]', '', 'g')
    and not b.is_archived
  limit 1;

  if resolved_public_batch is null then
    raise exception 'The selected batch is not configured' using errcode = 'P0002';
  end if;

  insert into public.notices (author_id, title, body, priority, state, published_at)
  values (caller_profile_id, btrim(notice_title), coalesce(notice_body, ''), 'normal'::public.notice_priority, 'published', now())
  returning id into public_notice_id;

  insert into public.notice_targets (notice_id, batch_id)
  values (public_notice_id, resolved_public_batch);

  return jsonb_build_object('id', public_notice_id);
end;
$$;

commit;
