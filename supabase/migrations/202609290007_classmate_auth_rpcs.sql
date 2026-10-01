begin;

alter table classmate.profiles add column requested_batch_number smallint
  check (requested_batch_number between 0 and 99);

create function classmate.require_owner() returns void language plpgsql security definer
set search_path = '' as $$
begin
  if not classmate.is_owner() then raise exception 'Admin permission required' using errcode = '42501'; end if;
end;
$$;

create function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare u auth.users%rowtype; result classmate.profiles%rowtype; dept uuid;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$'
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed institutional Google account required' using errcode = '28000';
  end if;
  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    insert into classmate.profiles(id, email, full_name, role, verification_status)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', 'active')
    on conflict (id) do update set role = 'admin', verification_status = 'active',
      department_id = null, batch_id = null, student_id = null, is_cr = false,
      cr_batch_id = null, cr_valid_until = null, updated_at = now();
  else
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active')
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', updated_at = now();
    else
      insert into classmate.profiles(id, email, full_name, role, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'student', 'pending')
      on conflict (id) do update set role = 'student',
        verification_status = case when classmate.profiles.role <> 'student'
          then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
        department_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.department_id end,
        updated_at = now();
    end if;
  end if;
  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

create function classmate.complete_student_onboarding(
  selected_department uuid, entered_student_id text default null,
  entered_batch_number smallint default null, entered_session smallint default null
) returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare p classmate.profiles%rowtype; d classmate.departments%rowtype;
  matched text[]; derived_session smallint; derived_batch smallint; resolved_batch uuid;
begin
  p := classmate.create_or_initialize_profile();
  if p.role <> 'student' then raise exception 'Student onboarding only' using errcode = '42501'; end if;
  if p.department_id is not null then raise exception 'Onboarding already submitted'; end if;
  select * into d from classmate.departments where id = selected_department and is_active;
  if d.id is null then raise exception 'Department unavailable'; end if;
  if d.email_prefix is not null then
    matched := regexp_match(p.email, '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if matched is null or matched[1] <> d.email_prefix then
      raise exception 'Institutional email does not match department';
    end if;
    derived_session := matched[2]::smallint;
    derived_batch := derived_session - d.session_offset;
    if derived_batch < 0 then raise exception 'Invalid department session offset'; end if;
    insert into classmate.batches(department_id, batch_number, academic_session)
    values (d.id, derived_batch, derived_session)
    on conflict (department_id, batch_number) do nothing;
    select id into resolved_batch from classmate.batches
      where department_id = d.id and batch_number = derived_batch
        and academic_session = derived_session and is_active;
    if resolved_batch is null then raise exception 'Batch/session conflict'; end if;
    update classmate.profiles set department_id = d.id, batch_id = resolved_batch,
      student_id = upper(matched[1] || matched[2] || matched[3]),
      academic_session = derived_session, requested_batch_number = null,
      profile_source = 'auto', verification_status = 'active', rejection_reason = null,
      updated_at = now() where id = p.id returning * into p;
  else
    if entered_student_id is null or trim(entered_student_id) !~ '^[A-Za-z0-9-]{4,30}$'
      or entered_batch_number is null or entered_batch_number not between 0 and 99
      or entered_session is null or entered_session not between 0 and 99 then
      raise exception 'Student ID, batch, and session are required';
    end if;
    update classmate.profiles set department_id = d.id, batch_id = null,
      student_id = upper(trim(entered_student_id)), academic_session = entered_session,
      requested_batch_number = entered_batch_number, profile_source = 'manual',
      verification_status = 'pending', rejection_reason = null, updated_at = now()
    where id = p.id returning * into p;
  end if;
  return p;
end;
$$;

create function classmate.create_department(target_name text, target_code text)
returns classmate.departments language plpgsql security definer set search_path = '' as $$
declare d classmate.departments%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.departments(name, code, is_active)
  values (trim(target_name), lower(trim(target_code)), false) returning * into d;
  insert into classmate.audit_log(actor_id, action, target_id) values (auth.uid(), 'create_department', d.id);
  return d;
end;
$$;
create function classmate.configure_department(target_department uuid, target_prefix text,
  target_offset smallint, target_active boolean)
returns classmate.departments language plpgsql security definer set search_path = '' as $$
declare d classmate.departments%rowtype;
begin
  perform classmate.require_owner();
  update classmate.departments set email_prefix = nullif(lower(trim(target_prefix)), ''),
    session_offset = target_offset, is_active = target_active, updated_at = now()
  where id = target_department returning * into d;
  if d.id is null then raise exception 'Department not found'; end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'configure_department', d.id);
  return d;
end;
$$;
create function classmate.create_batch(target_department uuid, target_batch_number smallint,
  target_session smallint) returns classmate.batches language plpgsql security definer
set search_path = '' as $$
declare b classmate.batches%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.batches(department_id, batch_number, academic_session)
  values (target_department, target_batch_number, target_session) returning * into b;
  insert into classmate.audit_log(actor_id, action, target_id) values (auth.uid(), 'create_batch', b.id);
  return b;
end;
$$;

create function classmate.approve_student_profile(target_profile uuid, corrected_student_id text,
  target_batch uuid, corrected_session smallint)
returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare p classmate.profiles%rowtype; b classmate.batches%rowtype;
begin
  perform classmate.require_owner();
  select * into p from classmate.profiles where id = target_profile for update;
  if p.id is null or p.role <> 'student' or p.profile_source <> 'manual'
    or p.verification_status not in ('pending', 'rejected') then
    raise exception 'Manual student profile not awaiting approval';
  end if;
  select * into b from classmate.batches where id = target_batch;
  if b.id is null or b.department_id <> p.department_id or not b.is_active then
    raise exception 'Batch must be active in selected department';
  end if;
  if corrected_student_id is null or trim(corrected_student_id) !~ '^[A-Za-z0-9-]{4,30}$'
    or corrected_session is distinct from b.academic_session then
    raise exception 'Student ID or academic session does not match batch';
  end if;
  update classmate.profiles set student_id = upper(trim(corrected_student_id)),
    academic_session = corrected_session, batch_id = b.id, requested_batch_number = null,
    verification_status = 'active', rejection_reason = null, updated_at = now()
  where id = p.id returning * into p;
  insert into classmate.audit_log(actor_id, action, target_id,
    details) values (auth.uid(), 'approve_student_profile', p.id,
    jsonb_build_object('batch_id', b.id, 'corrected', true));
  return p;
end;
$$;
create function classmate.reject_student_profile(target_profile uuid, reason text)
returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare p classmate.profiles%rowtype;
begin
  perform classmate.require_owner();
  if reason is null or length(trim(reason)) < 3 then raise exception 'Rejection reason required'; end if;
  update classmate.profiles set verification_status = 'rejected', rejection_reason = trim(reason),
    updated_at = now() where id = target_profile and role = 'student'
      and profile_source = 'manual' and verification_status = 'pending'
  returning * into p;
  if p.id is null then raise exception 'Pending manual profile not found'; end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'reject_student_profile', p.id);
  return p;
end;
$$;

create function classmate.assign_cr(target_profile uuid, target_batch uuid,
  valid_until timestamptz default null) returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare p classmate.profiles%rowtype;
begin
  perform classmate.require_owner();
  if valid_until is not null and valid_until <= now() then raise exception 'Expiry must be in future'; end if;
  update classmate.profiles set is_cr = true, cr_batch_id = target_batch,
    cr_valid_until = valid_until, updated_at = now()
  where id = target_profile and role = 'student' and verification_status = 'active'
    and batch_id = target_batch returning * into p;
  if p.id is null then raise exception 'Active student does not belong to batch'; end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'assign_cr', p.id);
  return p;
end;
$$;
create function classmate.revoke_cr(target_profile uuid) returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare p classmate.profiles%rowtype;
begin
  perform classmate.require_owner();
  update classmate.profiles set is_cr = false, cr_batch_id = null, cr_valid_until = null,
    updated_at = now() where id = target_profile and role = 'student' and is_cr
  returning * into p;
  if p.id is null then raise exception 'CR not found'; end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'revoke_cr', p.id);
  return p;
end;
$$;

create function classmate.set_teacher_allowlist(target_email text, target_department uuid,
  target_active boolean) returns classmate.teacher_allowlist language plpgsql
security definer set search_path = '' as $$
declare a classmate.teacher_allowlist%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.teacher_allowlist(email, department_id, active, created_by)
  values (lower(trim(target_email)), target_department, target_active, auth.uid())
  on conflict (email, department_id) do update set active = excluded.active
  returning * into a;
  if not target_active then
    update classmate.teacher_course_assignments set active = false
    where teacher_id in (select id from classmate.profiles where email = a.email)
      and semester_course_id in (select sc.id from classmate.semester_courses sc
        join classmate.semesters s on s.id = sc.semester_id
        join classmate.batches b on b.id = s.batch_id
        where b.department_id = a.department_id);
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'set_teacher_allowlist', a.id);
  return a;
end;
$$;
create function classmate.assign_teacher_to_course(target_teacher uuid,
  target_semester_course uuid) returns classmate.teacher_course_assignments
language plpgsql security definer set search_path = '' as $$
declare a classmate.teacher_course_assignments%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
  values (target_teacher, target_semester_course, auth.uid())
  on conflict (teacher_id, semester_course_id) do update set active = true,
    assigned_by = excluded.assigned_by returning * into a;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'assign_teacher_to_course', a.id);
  return a;
end;
$$;

create function classmate.create_course(target_department uuid, target_code text,
  target_title text, target_credit numeric, target_type text)
returns classmate.courses language plpgsql security definer set search_path = '' as $$
declare c classmate.courses%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.courses(department_id, course_code, course_title, credit, course_type)
  values (target_department, upper(trim(target_code)), trim(target_title), target_credit, target_type)
  returning * into c;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'create_course', c.id);
  return c;
end;
$$;
create function classmate.add_semester_course(target_semester uuid, target_course uuid)
returns classmate.semester_courses language plpgsql security definer set search_path = '' as $$
declare sc classmate.semester_courses%rowtype;
begin
  perform classmate.require_owner();
  if not exists (select 1 from classmate.semesters
    where id = target_semester and status = 'not_started') then
    raise exception 'Only an unpublished semester can be edited';
  end if;
  insert into classmate.semester_courses(semester_id, course_id)
  values (target_semester, target_course) returning * into sc;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'add_semester_course', sc.id);
  return sc;
end;
$$;
create function classmate.add_routine_slot(target_semester_course uuid, target_day smallint,
  target_start time, target_end time, target_room text, target_type text)
returns classmate.routine_slots language plpgsql security definer set search_path = '' as $$
declare slot classmate.routine_slots%rowtype;
begin
  perform classmate.require_owner();
  if not exists (select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_semester_course and s.status = 'not_started') then
    raise exception 'Only an unpublished semester can be edited';
  end if;
  insert into classmate.routine_slots(semester_course_id, day_of_week,
    start_time, end_time, room, type)
  values (target_semester_course, target_day, target_start, target_end,
    target_room, coalesce(target_type, 'class')) returning * into slot;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'add_routine_slot', slot.id);
  return slot;
end;
$$;

create function classmate.save_bus_schedule(target_id uuid, target_route text,
  target_departure time, target_origin text, target_destination text,
  target_weekdays smallint[], target_notes text, target_active boolean)
returns classmate.bus_schedules language plpgsql security definer set search_path = '' as $$
declare bus classmate.bus_schedules%rowtype;
begin
  perform classmate.require_owner();
  if target_id is null then
    insert into classmate.bus_schedules(route_name, departure_time, origin, destination,
      weekdays, notes, active, created_by)
    values (trim(target_route), target_departure, trim(target_origin),
      trim(target_destination), target_weekdays, target_notes, target_active, auth.uid())
    returning * into bus;
  else
    update classmate.bus_schedules set route_name = trim(target_route),
      departure_time = target_departure, origin = trim(target_origin),
      destination = trim(target_destination), weekdays = target_weekdays,
      notes = target_notes, active = target_active, updated_at = now()
    where id = target_id returning * into bus;
    if bus.id is null then raise exception 'Bus schedule not found'; end if;
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'save_bus_schedule', bus.id);
  return bus;
end;
$$;

create function classmate.publish_semester(target_semester uuid) returns classmate.semesters
language plpgsql security definer set search_path = '' as $$
declare s classmate.semesters%rowtype;
begin
  perform classmate.require_owner();
  select * into s from classmate.semesters where id = target_semester;
  if s.id is null then raise exception 'Semester not found'; end if;
  perform 1 from classmate.batches where id = s.batch_id for update;
  select * into s from classmate.semesters where id = target_semester for update;
  if s.status = 'completed' then raise exception 'Completed semester cannot be republished'; end if;
  update classmate.semesters set status = 'completed'
  where batch_id = s.batch_id and status = 'active' and id <> s.id;
  update classmate.semesters set status = 'active', published_at = now()
  where id = s.id returning * into s;
  insert into classmate.notification_outbox(kind, record_id, department_id, batch_id, routing_label)
  select 'semester', s.id, b.department_id, b.id, classmate.routing_label(b.id)
  from classmate.batches b where b.id = s.batch_id
  on conflict (kind, record_id) do nothing;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'publish_semester', s.id);
  return s;
end;
$$;

create function classmate.clone_semester(from_id uuid, to_id uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare source classmate.semesters%rowtype; target classmate.semesters%rowtype;
  old_course record; new_course uuid;
begin
  perform classmate.require_owner();
  select * into source from classmate.semesters where id = from_id;
  select * into target from classmate.semesters where id = to_id for update;
  if source.id is null or target.id is null or source.id = target.id then raise exception 'Invalid semesters'; end if;
  if target.status <> 'not_started' then raise exception 'Target semester already published'; end if;
  if exists (select 1 from classmate.semester_courses where semester_id = target.id) then
    raise exception 'Target semester must be empty';
  end if;
  if exists (select 1 from classmate.semester_courses sc
      join classmate.courses c on c.id = sc.course_id
      join classmate.batches b on b.id = target.batch_id
      where sc.semester_id = source.id and c.department_id <> b.department_id) then
    raise exception 'Cross-department clone requires course mapping';
  end if;
  for old_course in select * from classmate.semester_courses where semester_id = source.id loop
    insert into classmate.semester_courses(semester_id, course_id)
    values (target.id, old_course.course_id) returning id into new_course;
    insert into classmate.routine_slots(semester_course_id, day_of_week, start_time, end_time, room, type)
    select new_course, day_of_week, start_time, end_time, room, type
    from classmate.routine_slots where semester_course_id = old_course.id;
    insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
    select teacher_id, new_course, auth.uid() from classmate.teacher_course_assignments
    where semester_course_id = old_course.id and active;
  end loop;
  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (auth.uid(), 'clone_semester', target.id, jsonb_build_object('from_id', source.id));
  return target.id;
end;
$$;

create function classmate.post_notice(target_batch uuid, target_course uuid,
  notice_title text, notice_body text) returns classmate.notices
language plpgsql security definer set search_path = '' as $$
declare n classmate.notices%rowtype; d uuid;
begin
  if not classmate.can_post(target_batch, target_course) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  if target_course is not null and not exists (
    select 1 from classmate.semester_courses sc join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_course and s.batch_id = target_batch and s.status = 'active') then
    raise exception 'Course semester is not active';
  end if;
  select department_id into d from classmate.batches where id = target_batch;
  insert into classmate.notices(department_id, batch_id, semester_course_id,
    author_id, title, body)
  values (d, target_batch, target_course, auth.uid(), trim(notice_title), coalesce(notice_body, ''))
  returning * into n;
  return n;
end;
$$;

create function classmate.post_class_change(target_batch uuid, target_course uuid,
  change_kind text, change_date date, change_details text)
returns classmate.class_changes language plpgsql security definer set search_path = '' as $$
declare change_row classmate.class_changes%rowtype; d uuid;
begin
  if target_course is null or not classmate.can_post(target_batch, target_course) then
    raise exception 'Course assignment required' using errcode = '42501';
  end if;
  if not exists (select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_course and s.batch_id = target_batch and s.status = 'active') then
    raise exception 'Course semester is not active';
  end if;
  select department_id into d from classmate.batches where id = target_batch;
  insert into classmate.class_changes(department_id, batch_id, semester_course_id,
    author_id, kind, effective_date, details)
  values (d, target_batch, target_course, auth.uid(), change_kind,
    change_date, coalesce(change_details, '')) returning * into change_row;
  return change_row;
end;
$$;

create function classmate.register_device_token(target_token text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode = '42501'; end if;
  if target_token is null or length(target_token) < 20 or length(target_token) > 4096 then
    raise exception 'Invalid FCM token';
  end if;
  insert into classmate.device_tokens(profile_id, fcm_token)
  values (auth.uid(), target_token)
  on conflict (fcm_token) do update set profile_id = excluded.profile_id, updated_at = now();
end;
$$;

-- Allowlist deactivation must revoke effective privileges immediately.
create or replace function classmate.can_manage_course(target_semester_course uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.teacher_course_assignments a
    join classmate.profiles p on p.id = a.teacher_id
    join classmate.teacher_allowlist t on t.email = p.email and t.active
    join classmate.semester_courses sc on sc.id = a.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id and b.department_id = t.department_id
    where a.teacher_id = (select auth.uid()) and a.semester_course_id = target_semester_course
      and a.active and p.role = 'teacher' and p.verification_status = 'active'
  );
$$;

revoke all on all functions in schema classmate from public, anon, authenticated;
grant execute on function classmate.is_owner() to authenticated;
grant execute on function classmate.is_active() to authenticated;
grant execute on function classmate.can_read_batch(uuid) to authenticated;
grant execute on function classmate.can_manage_course(uuid) to authenticated;
grant execute on function classmate.can_post(uuid,uuid) to authenticated;
grant execute on function classmate.create_or_initialize_profile() to authenticated;
grant execute on function classmate.complete_student_onboarding(uuid,text,smallint,smallint) to authenticated;
grant execute on function classmate.create_department(text,text) to authenticated;
grant execute on function classmate.configure_department(uuid,text,smallint,boolean) to authenticated;
grant execute on function classmate.create_batch(uuid,smallint,smallint) to authenticated;
grant execute on function classmate.approve_student_profile(uuid,text,uuid,smallint) to authenticated;
grant execute on function classmate.reject_student_profile(uuid,text) to authenticated;
grant execute on function classmate.assign_cr(uuid,uuid,timestamptz) to authenticated;
grant execute on function classmate.revoke_cr(uuid) to authenticated;
grant execute on function classmate.set_teacher_allowlist(text,uuid,boolean) to authenticated;
grant execute on function classmate.assign_teacher_to_course(uuid,uuid) to authenticated;
grant execute on function classmate.create_course(uuid,text,text,numeric,text) to authenticated;
grant execute on function classmate.add_semester_course(uuid,uuid) to authenticated;
grant execute on function classmate.add_routine_slot(uuid,smallint,time,time,text,text) to authenticated;
grant execute on function classmate.save_bus_schedule(uuid,text,time,text,text,smallint[],text,boolean) to authenticated;
grant execute on function classmate.publish_semester(uuid) to authenticated;
grant execute on function classmate.clone_semester(uuid,uuid) to authenticated;
grant execute on function classmate.post_notice(uuid,uuid,text,text) to authenticated;
grant execute on function classmate.post_class_change(uuid,uuid,text,date,text) to authenticated;
grant execute on function classmate.register_device_token(text) to authenticated;

commit;
