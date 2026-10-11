begin;

-- ============================================================================
-- 1. Schema Additions
-- ============================================================================
alter table classmate.batches 
  add column if not exists calendar_in_timetable boolean not null default true;

alter table classmate.semester_courses 
  add column if not exists attendance_enabled boolean not null default false;

-- ============================================================================
-- 2. Batch Timetable Calendar Toggle RPC
-- ============================================================================
create or replace function classmate.toggle_batch_calendar_in_timetable(
  target_batch uuid,
  enabled boolean
)
returns boolean
language plpgsql security definer set search_path='' as $$
begin
  if not (classmate.is_owner() or classmate.can_manage_batch(target_batch)) then
    raise exception 'Unauthorized to change batch settings' using errcode='42501';
  end if;

  update classmate.batches
  set calendar_in_timetable = coalesce(enabled, true)
  where id = target_batch;

  return true;
end $$;

-- ============================================================================
-- 3. Course Attendance Opt-in Toggle RPC
-- ============================================================================
create or replace function classmate.toggle_course_attendance(
  target_course uuid,
  enabled boolean
)
returns boolean
language plpgsql security definer set search_path='' as $$
declare
  v_batch uuid;
begin
  select s.batch_id into v_batch
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  where sc.id = target_course;

  if v_batch is null then
    raise exception 'Course not found' using errcode='42704';
  end if;

  if not (classmate.is_owner() or classmate.can_manage_batch(v_batch)) then
    raise exception 'Unauthorized to change course attendance settings' using errcode='42501';
  end if;

  update classmate.semester_courses
  set attendance_enabled = coalesce(enabled, false)
  where id = target_course;

  return true;
end $$;

-- ============================================================================
-- 4. Batch Attendance Courses Catalog (for admin toggle in settings)
-- ============================================================================
create or replace function classmate.batch_attendance_courses(target_batch uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode='42501';
  end if;

  return coalesce((
    select jsonb_agg(
      jsonb_build_object(
        'offering_id', sc.id,
        'course_code', coalesce(sc.code_override, c.course_code),
        'course_title', coalesce(sc.title_override, c.course_title),
        'course_type', coalesce(sc.type_override, c.course_type),
        'credit', coalesce(sc.credit_override, c.credit),
        'teacher_name', coalesce(t.full_name, 'No teacher assigned'),
        'attendance_enabled', coalesce(sc.attendance_enabled, false)
      ) order by coalesce(sc.code_override, c.course_code) asc
    )
    from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    left join classmate.teacher_directory t on t.id = sc.teacher_record_id
    where s.batch_id = target_batch and s.status in ('active', 'not_started')
  ), '[]'::jsonb);
end $$;

-- ============================================================================
-- 5. Update available_batches to include calendar_in_timetable
-- ============================================================================
create or replace function classmate.available_batches() 
returns jsonb language plpgsql stable security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 return coalesce((select jsonb_agg(to_jsonb(q)) from(select b.id,b.department_id,b.batch_number,b.academic_session,
   coalesce(b.calendar_in_timetable, true) as calendar_in_timetable,
   jsonb_build_object('code',d.code,'name',d.name) departments
 from classmate.batches b join classmate.departments d on d.id=b.department_id
 where b.is_active and classmate.can_read_batch(b.id) and (
 not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher') or exists(
 select 1 from classmate.teacher_course_assignments a join classmate.semester_courses sc on sc.id=a.semester_course_id join classmate.semesters s on s.id=sc.semester_id
 where a.teacher_id=auth.uid() and a.active and s.status='active' and s.batch_id=b.id))
 order by d.code,b.batch_number)q),'[]'::jsonb);
end $$;

-- ============================================================================
-- 6. Update batch_timetable_catalog to return attendance_enabled
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
  attendance_enabled boolean
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
    coalesce(sc.attendance_enabled, false)
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  left join classmate.teacher_directory t on t.id = sc.teacher_record_id
  where s.batch_id = target_batch and s.status = 'active'
  order by coalesce(sc.code_override, c.course_code), sc.id;
end $$;

-- ============================================================================
-- 7. Continuous Single Attendance Session per Day
-- ============================================================================
create or replace function classmate.start_attendance_session(
  target_slot uuid,
  client_secret text default null
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess_id uuid;
  sec text;
  v_batch uuid;
  v_dept uuid;
  v_course_title text;
  today_date date;
  existing_sess classmate.attendance_sessions%rowtype;
begin
  if not classmate.can_manage_attendance_slot(target_slot) then
    raise exception 'Unauthorized to start attendance for this routine slot' using errcode='42501';
  end if;

  today_date := (now() at time zone 'Asia/Dhaka')::date;

  -- Check if a session already exists today for this routine slot
  select * into existing_sess
  from classmate.attendance_sessions
  where routine_slot_id = target_slot
    and (started_at at time zone 'Asia/Dhaka')::date = today_date
  order by started_at desc
  limit 1;

  if existing_sess.id is not null then
    -- Reuse existing session! If closed earlier, reopen it for the live class period
    if existing_sess.status <> 'open' then
      update classmate.attendance_sessions
      set status = 'open', closed_at = null
      where id = existing_sess.id;
    end if;

    return jsonb_build_object(
      'session_id', existing_sess.id,
      'session_secret', existing_sess.session_secret,
      'status', 'open',
      'is_resumed', true
    );
  end if;

  -- Create single session for today
  sec := coalesce(nullif(trim(client_secret), ''), encode(extensions.gen_random_bytes(24), 'hex'));

  insert into classmate.attendance_sessions (routine_slot_id, started_by, session_secret, status)
  values (target_slot, auth.uid(), sec, 'open')
  returning id into sess_id;

  -- Dispatch push notification to enrolled batch
  select s.batch_id, b.department_id, c.course_title
  into v_batch, v_dept, v_course_title
  from classmate.routine_slots rs
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.batches b on b.id = s.batch_id
  where rs.id = target_slot;

  if v_batch is not null then
    insert into classmate.notification_outbox(
      kind, record_id, batch_id, department_id, routing_label
    ) values (
      'attendance_open', sess_id, v_batch, v_dept, classmate.routing_label(v_batch)
    ) on conflict do nothing;
    perform classmate.request_notification_dispatch();
  end if;

  return jsonb_build_object(
    'session_id', sess_id,
    'session_secret', sec,
    'status', 'open',
    'is_resumed', false
  );
end $$;

-- ============================================================================
-- 8. Enhanced student_my_attendance with complete session date history
-- ============================================================================
drop function if exists classmate.student_my_attendance(uuid);

create or replace function classmate.student_my_attendance(target_batch uuid default null)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid := target_batch;
  courses_list jsonb;
  total_held int := 0;
  total_attended int := 0;
begin
  if not classmate.is_active() then
    raise exception 'Active profile required' using errcode='42501';
  end if;

  if v_batch is null then
    select batch_id into v_batch from classmate.profiles where id = caller_id;
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'semester_course_id', sc.id,
        'course_code', coalesce(sc.code_override, c.course_code),
        'course_title', coalesce(sc.title_override, c.course_title),
        'course_type', coalesce(sc.type_override, c.course_type),
        'attendance_enabled', coalesce(sc.attendance_enabled, false),
        'classes_held', coalesce(c_stat.held, 0),
        'classes_attended', coalesce(c_stat.attended, 0),
        'percentage', case 
          when coalesce(c_stat.held, 0) > 0 
          then round((coalesce(c_stat.attended, 0)::numeric / c_stat.held::numeric) * 100, 1)
          else 0 end,
        'history', coalesce(c_stat.history, '[]'::jsonb)
      ) order by coalesce(sc.code_override, c.course_code) asc
    ),
    '[]'::jsonb
  )
  into courses_list
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters s on s.id = sc.semester_id
  left join lateral (
    select
      count(distinct sess.id) as held,
      count(distinct r.session_id) as attended,
      coalesce(
        jsonb_agg(
          jsonb_build_object(
            'session_id', sess.id,
            'date', (sess.started_at at time zone 'Asia/Dhaka')::date,
            'time', to_char((sess.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
            'room', coalesce(rs.room, 'TBA'),
            'status', case when r.id is not null then 'present' else 'absent' end,
            'marked_at', r.created_at
          ) order by sess.started_at desc
        ) filter (where sess.id is not null),
        '[]'::jsonb
      ) as history
    from classmate.routine_slots rs
    join classmate.attendance_sessions sess on sess.routine_slot_id = rs.id
    left join classmate.attendance_records r on r.session_id = sess.id and r.student_id = caller_id
    where rs.semester_course_id = sc.id
  ) c_stat on true
  where s.batch_id = v_batch and s.status in ('active', 'not_started');

  -- Calculate overall metrics
  select
    coalesce(sum((c->>'classes_held')::int), 0),
    coalesce(sum((c->>'classes_attended')::int), 0)
  into total_held, total_attended
  from jsonb_array_elements(courses_list) c;

  return jsonb_build_object(
    'overall_percentage', case when total_held > 0 then round((total_attended::numeric / total_held::numeric) * 100, 1) else 0 end,
    'total_classes_held', total_held,
    'total_classes_attended', total_attended,
    'courses', courses_list
  );
end $$;

-- ============================================================================
-- 9. Grants & Permissions
-- ============================================================================
revoke all on function classmate.toggle_batch_calendar_in_timetable(uuid, boolean) from public, anon;
grant execute on function classmate.toggle_batch_calendar_in_timetable(uuid, boolean) to authenticated;

revoke all on function classmate.toggle_course_attendance(uuid, boolean) from public, anon;
grant execute on function classmate.toggle_course_attendance(uuid, boolean) to authenticated;

revoke all on function classmate.batch_attendance_courses(uuid) from public, anon;
grant execute on function classmate.batch_attendance_courses(uuid) to authenticated;

revoke all on function classmate.batch_timetable_catalog(uuid) from public, anon;
grant execute on function classmate.batch_timetable_catalog(uuid) to authenticated;

commit;

notify pgrst, 'reload schema';
