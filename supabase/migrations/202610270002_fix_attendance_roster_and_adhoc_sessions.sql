-- ============================================================================
-- Migration: 202610270002_fix_attendance_roster_and_adhoc_sessions.sql
-- Description:
-- 1. Helper function can_manage_attendance_session supporting ad-hoc and slot sessions
-- 2. get_session_summary fix: resolve batch and return full roster for both ad-hoc & scheduled sessions
-- 3. close_attendance_session & toggle_student_attendance: support ad-hoc sessions & unlink hardware
-- 4. teacher_course_attendance_overview: include ad-hoc sessions and grant CR access
-- 5. course_attendance_matrix: grant CR access for their batch
-- 6. start_adhoc_attendance_session & close_adhoc_attendance_session: allow CR access
-- 7. Update RLS policies on attendance_sessions and attendance_records for CRs and ad-hoc sessions
-- ============================================================================

begin;

-- 1. Helper to verify session management permissions for both ad-hoc and routine sessions
create or replace function classmate.can_manage_attendance_session(target_session uuid)
returns boolean
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  sess record;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then return true; end if;
    return false;
  end if;

  if current_user in ('postgres', 'supabase_admin') or classmate.is_owner() then
    return true;
  end if;

  select s.id, s.routine_slot_id, s.semester_course_id, s.batch_id, s.started_by
  into sess
  from classmate.attendance_sessions s
  where s.id = target_session;

  if not found then return false; end if;

  -- Session creator or Admin
  if sess.started_by = caller_id then return true; end if;
  if exists (select 1 from classmate.profiles where id = caller_id and role = 'admin') then return true; end if;

  -- Routine slot check
  if sess.routine_slot_id is not null and classmate.can_manage_attendance_slot(sess.routine_slot_id) then
    return true;
  end if;

  -- Course assignment check (Teacher)
  if sess.semester_course_id is not null and exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = sess.semester_course_id and tca.teacher_id = caller_id and tca.active
  ) then
    return true;
  end if;

  -- Batch CR check
  if sess.batch_id is not null and exists (
    select 1 from classmate.profiles
    where id = caller_id and is_cr = true and batch_id = sess.batch_id
  ) then
    return true;
  end if;

  -- Check batch via routine_slot if batch_id was null on session
  if sess.routine_slot_id is not null and exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.profiles p on p.id = caller_id
    where rs.id = sess.routine_slot_id and p.is_cr = true and p.batch_id = sem.batch_id
  ) then
    return true;
  end if;

  return false;
end;
$$;

grant execute on function classmate.can_manage_attendance_session(uuid) to authenticated;

-- 2. Update get_session_summary to support ad-hoc sessions and return full roster
create or replace function classmate.get_session_summary(target_session uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  sess record;
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  roster jsonb;
begin
  select s.id, s.routine_slot_id, s.semester_course_id, s.started_by, s.started_at, s.closed_at, s.status, s.batch_id
  into sess
  from classmate.attendance_sessions s
  where s.id = target_session;

  if not found then
    raise exception 'Session not found' using errcode='P0002';
  end if;

  -- Resolve batch_id and course details
  v_batch_id := sess.batch_id;

  if sess.routine_slot_id is not null then
    select sem.batch_id,
           coalesce(sc.code_override, c.course_code),
           coalesce(sc.title_override, c.course_title)
    into v_batch_id, v_course_code, v_course_title
    from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    where rs.id = sess.routine_slot_id;
  elsif sess.semester_course_id is not null then
    select sem.batch_id,
           coalesce(sc.code_override, c.course_code),
           coalesce(sc.title_override, c.course_title)
    into v_batch_id, v_course_code, v_course_title
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    where sc.id = sess.semester_course_id;
  end if;

  -- Explicit session batch_id takes precedence if set
  if sess.batch_id is not null then
    v_batch_id := sess.batch_id;
  end if;

  -- Build complete roster of all students in batch, with present/absent status
  select jsonb_agg(jsonb_build_object(
    'student_id', p.id,
    'full_name', p.full_name,
    'roll_number', coalesce(p.student_id, ''),
    'status', case when r.id is not null then 'present' else 'absent' end,
    'marked_at', r.marked_at,
    'method', coalesce(r.method, 'none'),
    'confidence', coalesce(r.confidence, 'none'),
    'rfid_card_uid', p.rfid_card_uid
  ) order by coalesce(p.student_id, p.full_name) asc)
  into roster
  from classmate.profiles p
  left join classmate.attendance_records r on r.session_id = target_session and r.student_id = p.id
  where (
    (v_batch_id is not null and p.batch_id = v_batch_id)
    or (v_batch_id is null and r.id is not null)
  )
  and p.role in ('student', 'admin')
  and coalesce(p.verification_status, 'active') = 'active';

  return jsonb_build_object(
    'session_id', sess.id,
    'routine_slot_id', sess.routine_slot_id,
    'semester_course_id', sess.semester_course_id,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch_id,
    'status', sess.status,
    'started_at', sess.started_at,
    'closed_at', sess.closed_at,
    'roster', coalesce(roster, '[]'::jsonb)
  );
end $$;

grant execute on function classmate.get_session_summary(uuid) to authenticated, anon;

-- 3. Close attendance session supporting both routine & ad-hoc sessions
create or replace function classmate.close_attendance_session(target_session uuid)
returns void
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found' using errcode='P0002'; end if;

  if not classmate.can_manage_attendance_session(target_session) then
    raise exception 'Unauthorized to close attendance session' using errcode='42501';
  end if;

  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where id = target_session;

  -- Unlink hardware attendance reader
  update classmate.attendance_devices
  set active_session_id = null
  where active_session_id = target_session;
end $$;

grant execute on function classmate.close_attendance_session(uuid) to authenticated;

-- 4. Toggle student attendance supporting both routine & ad-hoc sessions
create or replace function classmate.toggle_student_attendance(
  target_session uuid,
  target_student uuid,
  present boolean
)
returns void
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found' using errcode='P0002'; end if;

  if not classmate.can_manage_attendance_session(target_session) then
    raise exception 'Unauthorized to manage attendance for this session' using errcode='42501';
  end if;

  if present then
    insert into classmate.attendance_records(session_id, student_id, method, confidence)
    values (target_session, target_student, 'manual', 'admin_override')
    on conflict (session_id, student_id) do update set confidence = 'admin_override', synced_at = now();
  else
    delete from classmate.attendance_records
    where session_id = target_session and student_id = target_student;
  end if;
end $$;

grant execute on function classmate.toggle_student_attendance(uuid, uuid, boolean) to authenticated;

-- 5. Start Ad-Hoc Attendance Session: Allow CRs as well
create or replace function classmate.start_adhoc_attendance_session(
  target_semester_course uuid,
  target_device text default 'ESP32-READER-01'
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid;
  v_dept uuid;
  v_course_code text;
  v_course_title text;
  sess_id uuid;
  sec text;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select sem.batch_id, b.department_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch, v_dept, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters sem on sem.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  join classmate.batches b on b.id = sem.batch_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  -- Check permissions (Admin, Owner, assigned teacher, or Batch CR)
  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and role = 'admin')
    or exists (
      select 1 from classmate.teacher_course_assignments tca
      where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
    )
    or exists (
      select 1 from classmate.profiles p
      where p.id = caller_id and p.is_cr = true and p.batch_id = v_batch
    )
  ) then
    raise exception 'Unauthorized to start attendance session for this course' using errcode='42501';
  end if;

  -- Close any existing open session for this course to avoid stale duplicates
  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where semester_course_id = target_semester_course and status = 'open';

  sec := encode(extensions.gen_random_bytes(16), 'hex');

  insert into classmate.attendance_sessions (
    routine_slot_id, semester_course_id, batch_id, started_by, session_secret, status
  ) values (
    null, target_semester_course, v_batch, caller_id, sec, 'open'
  )
  returning id into sess_id;

  -- Automatically bind hardware device if specified
  if target_device is not null then
    update classmate.attendance_devices
    set active_session_id = sess_id, last_heartbeat = now()
    where id = target_device;
  end if;

  -- Queue attendance open notification for batch
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label
  ) values (
    'attendance_open', sess_id, v_batch, v_dept, classmate.routing_label(v_batch)
  ) on conflict (kind, record_id) do update set
    delivered_at = null,
    jobs_prepared = false,
    attempts = 0,
    created_at = now();

  perform classmate.request_notification_dispatch();

  return jsonb_build_object(
    'session_id', sess_id,
    'session_secret', sec,
    'status', 'open',
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch
  );
end $$;

grant execute on function classmate.start_adhoc_attendance_session(uuid, text) to authenticated;

-- 6. Close Ad-Hoc Attendance Session: Allow CRs as well
create or replace function classmate.close_adhoc_attendance_session(target_session uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  sess record;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select s.id, s.status, s.started_by, s.batch_id, s.semester_course_id
  into sess
  from classmate.attendance_sessions s
  where s.id = target_session;

  if not found then
    raise exception 'Attendance session not found' using errcode='P0002';
  end if;

  if not classmate.can_manage_attendance_session(target_session) then
    raise exception 'Unauthorized to close attendance session' using errcode='42501';
  end if;

  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where id = target_session;

  -- Unlink device
  update classmate.attendance_devices
  set active_session_id = null
  where active_session_id = target_session;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', 'closed'
  );
end $$;

grant execute on function classmate.close_adhoc_attendance_session(uuid) to authenticated;

-- 7. Update teacher_course_attendance_overview: Include ad-hoc sessions and allow CRs
create or replace function classmate.teacher_course_attendance_overview(
  target_batch uuid,
  target_course uuid default null
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  caller_role text;
  caller_is_cr boolean := false;
  res jsonb;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
      is_admin := true;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select p.role, coalesce(p.is_cr, false)
  into caller_role, caller_is_cr
  from classmate.profiles p
  where p.id = caller_id;

  if not is_admin and coalesce(caller_role, '') not in ('teacher', 'admin') and not caller_is_cr then
    raise exception 'Unauthorized to view teacher attendance overview' using errcode='42501';
  end if;

  select coalesce(jsonb_agg(
    jsonb_build_object(
      'semester_course_id', course_data.offering_id,
      'offering_id', course_data.offering_id,
      'course_code', course_data.course_code,
      'course_title', course_data.course_title,
      'course_type', course_data.course_type,
      'credit', course_data.credit,
      'teacher_name', course_data.teacher_name,
      'attendance_enabled', course_data.attendance_enabled,
      'total_sessions', course_data.total_sessions,
      'enrolled_students', course_data.total_enrolled,
      'average_percentage', course_data.avg_percentage,
      'last_session_at', course_data.last_session_at,
      'last_session_present', course_data.last_session_present,
      'sessions', course_data.sessions
    ) order by course_data.course_code asc
  ), '[]'::jsonb) into res
  from (
    select
      sc.id as offering_id,
      coalesce(sc.code_override, c.course_code) as course_code,
      coalesce(sc.title_override, c.course_title) as course_title,
      coalesce(sc.type_override, c.course_type) as course_type,
      coalesce(sc.credit_override, c.credit, 3.0) as credit,
      coalesce(td.full_name, 'Instructor') as teacher_name,
      coalesce(sc.attendance_enabled, false) as attendance_enabled,
      count(distinct s.id) as total_sessions,
      coalesce(
        (
          select count(*)
          from classmate.profiles p
          where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
        ), 0
      ) as total_enrolled,
      case 
        when count(distinct s.id) > 0 and (
          select count(*) from classmate.profiles p 
          where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
        ) > 0
        then round((coalesce(sum(sess_stat.pres), 0)::numeric / (
          count(distinct s.id) * (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active')
        )::numeric) * 100, 1)
        else 0 end as avg_percentage,
      max(s.started_at) as last_session_at,
      coalesce((
        select count(r.id)
        from classmate.attendance_records r
        where r.session_id = (
          select s2.id from classmate.attendance_sessions s2
          left join classmate.routine_slots rs2 on rs2.id = s2.routine_slot_id
          where s2.semester_course_id = sc.id or rs2.semester_course_id = sc.id
          order by s2.started_at desc limit 1
        )
      ), 0) as last_session_present,
      coalesce(
        (
          select jsonb_agg(
            jsonb_build_object(
              'session_id', s_inner.id,
              'routine_slot_id', s_inner.routine_slot_id,
              'started_at', s_inner.started_at,
              'closed_at', s_inner.closed_at,
              'status', s_inner.status,
              'room', coalesce(rs_inner.room, 'Station'),
              'present_count', coalesce(rec_stat.pres_cnt, 0),
              'total_count', (
                select count(*)
                from classmate.profiles p
                where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
              ),
              'percentage', case
                when (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active') > 0
                then round((coalesce(rec_stat.pres_cnt, 0)::numeric / (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active')::numeric) * 100, 1)
                else 0 end
            ) order by s_inner.started_at desc
          )
          from classmate.attendance_sessions s_inner
          left join classmate.routine_slots rs_inner on rs_inner.id = s_inner.routine_slot_id
          left join lateral (
            select count(r_in.id) as pres_cnt
            from classmate.attendance_records r_in
            where r_in.session_id = s_inner.id
          ) rec_stat on true
          where s_inner.semester_course_id = sc.id or rs_inner.semester_course_id = sc.id
        ),
        '[]'::jsonb
      ) as sessions
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.routine_slots rs on rs.semester_course_id = sc.id
    left join classmate.attendance_sessions s on (s.routine_slot_id = rs.id or s.semester_course_id = sc.id)
    left join lateral (
      select count(r.id) as pres
      from classmate.attendance_records r
      where r.session_id = s.id
    ) sess_stat on true
    where sem.batch_id = target_batch
      and (target_course is null or sc.id = target_course)
      and (is_admin or caller_is_cr or coalesce(sc.attendance_enabled, false) = true)
      and (
        is_admin
        or caller_is_cr
        or exists (
          select 1 from classmate.teacher_course_assignments tca
          where tca.semester_course_id = sc.id and tca.teacher_id = caller_id and tca.active
        )
        or exists (
          select 1 from classmate.teacher_directory td_match
          join classmate.profiles pr on pr.id = caller_id
          where td_match.id = sc.teacher_record_id
            and (
              td_match.profile_id = caller_id
              or lower(td_match.email) = lower(pr.email)
              or split_part(lower(td_match.email), '@', 1) = split_part(lower(pr.email), '@', 1)
            )
        )
      )
    group by sc.id, c.course_code, c.course_title, c.course_type, c.credit, td.full_name, sem.batch_id
  ) course_data;

  return res;
end $$;

grant execute on function classmate.teacher_course_attendance_overview(uuid, uuid) to authenticated;

-- 8. Update course_attendance_matrix: Allow CRs to view matrix for courses in their batch
create or replace function classmate.course_attendance_matrix(
  target_semester_course uuid
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  sessions_list jsonb;
  students_list jsonb;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
      is_admin := true;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select s.batch_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch_id, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  if not is_admin and not exists (
    select 1 from classmate.profiles where id = caller_id and role = 'admin'
  ) and not exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
  ) and not exists (
    select 1 from classmate.profiles p
    where p.id = caller_id and p.is_cr = true and p.batch_id = v_batch_id
  ) then
    raise exception 'Unauthorized to view attendance matrix for this course' using errcode='42501';
  end if;

  -- Sessions for this course (both scheduled and ad-hoc)
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'session_id', s.id,
        'started_at', s.started_at,
        'date', (s.started_at at time zone 'Asia/Dhaka')::date,
        'time', to_char((s.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
        'status', s.status,
        'room', coalesce(rs.room, 'Station'),
        'total_present', coalesce(sess_present.pres_cnt, 0)
      ) order by s.started_at asc
    ),
    '[]'::jsonb
  )
  into sessions_list
  from classmate.attendance_sessions s
  left join classmate.routine_slots rs on rs.id = s.routine_slot_id
  left join lateral (
    select count(distinct r.student_id) as pres_cnt
    from classmate.attendance_records r
    where r.session_id = s.id
  ) sess_present on true
  where (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course);

  -- Enrolled students in batch
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'rfid_card_uid', p.rfid_card_uid,
        'records', coalesce(stu_records.rec_map, '{}'::jsonb),
        'present_count', coalesce(stu_records.pres_cnt, 0),
        'total_sessions', jsonb_array_length(sessions_list),
        'percentage', case 
          when jsonb_array_length(sessions_list) > 0 
          then round((coalesce(stu_records.pres_cnt, 0)::numeric / jsonb_array_length(sessions_list)::numeric) * 100, 1)
          else 0 end
      ) order by coalesce(p.student_id, p.full_name) asc
    ),
    '[]'::jsonb
  )
  into students_list
  from classmate.profiles p
  left join lateral (
    select
      jsonb_object_agg(
        r.session_id::text,
        jsonb_build_object(
          'present', true,
          'method', r.method,
          'marked_at', to_char((r.marked_at at time zone 'Asia/Dhaka'), 'HH12:MI AM')
        )
      ) as rec_map,
      count(distinct r.session_id) as pres_cnt
    from classmate.attendance_records r
    join classmate.attendance_sessions s on s.id = r.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    where r.student_id = p.id
      and (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course)
  ) stu_records on true
  where p.batch_id = v_batch_id and p.role in ('student', 'admin') and coalesce(p.verification_status, 'active') = 'active';

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'sessions', sessions_list,
    'students', students_list
  );
end $$;

grant execute on function classmate.course_attendance_matrix(uuid) to authenticated;

-- 9. Row-Level Security Policies for attendance_sessions
drop policy if exists view_attendance_sessions on classmate.attendance_sessions;
create policy view_attendance_sessions on classmate.attendance_sessions
  for select to authenticated
  using (
    classmate.is_owner()
    or started_by = auth.uid()
    or exists (
      select 1 from classmate.profiles p
      where p.id = auth.uid() and (p.role = 'admin' or p.is_cr = true)
    )
    or (
      classmate.attendance_sessions.batch_id is not null and exists (
        select 1 from classmate.profiles p
        where p.id = auth.uid() and p.batch_id = classmate.attendance_sessions.batch_id and p.verification_status = 'active'
      )
    )
    or exists (
      select 1 from classmate.routine_slots rs
      join classmate.semester_courses sc on sc.id = rs.semester_course_id
      join classmate.semesters s on s.id = sc.semester_id
      join classmate.profiles p on p.id = auth.uid()
      where rs.id = classmate.attendance_sessions.routine_slot_id
        and p.batch_id = s.batch_id and p.verification_status = 'active'
    )
    or exists (
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id
      join classmate.profiles p on p.id = auth.uid()
      where sc.id = classmate.attendance_sessions.semester_course_id
        and p.batch_id = s.batch_id and p.verification_status = 'active'
    )
    or exists (
      select 1 from classmate.teacher_course_assignments tca
      left join classmate.routine_slots rs on rs.id = classmate.attendance_sessions.routine_slot_id
      where (tca.semester_course_id = classmate.attendance_sessions.semester_course_id or tca.semester_course_id = rs.semester_course_id)
        and tca.teacher_id = auth.uid() and tca.active
    )
  );

drop policy if exists manage_attendance_sessions on classmate.attendance_sessions;
create policy manage_attendance_sessions on classmate.attendance_sessions
  for all to authenticated
  using (
    classmate.can_manage_attendance_session(id)
  );

-- 10. Row-Level Security Policies for attendance_records
drop policy if exists view_attendance_records on classmate.attendance_records;
create policy view_attendance_records on classmate.attendance_records
  for select to authenticated
  using (
    classmate.is_owner()
    or student_id = auth.uid()
    or exists (
      select 1 from classmate.profiles p
      where p.id = auth.uid() and p.role = 'admin'
    )
    or exists (
      select 1 from classmate.attendance_sessions s
      where s.id = attendance_records.session_id and (
        s.started_by = auth.uid()
        or exists (
          select 1 from classmate.profiles p
          where p.id = auth.uid() and p.is_cr = true and p.batch_id = s.batch_id
        )
      )
    )
    or exists (
      select 1 from classmate.attendance_sessions s
      left join classmate.routine_slots rs on rs.id = s.routine_slot_id
      join classmate.teacher_course_assignments tca on (tca.semester_course_id = s.semester_course_id or tca.semester_course_id = rs.semester_course_id)
      where s.id = attendance_records.session_id and tca.teacher_id = auth.uid() and tca.active
    )
  );

drop policy if exists manage_attendance_records on classmate.attendance_records;
create policy manage_attendance_records on classmate.attendance_records
  for all to authenticated
  using (
    classmate.can_manage_attendance_session(session_id)
  );

commit;
notify pgrst, 'reload schema';
