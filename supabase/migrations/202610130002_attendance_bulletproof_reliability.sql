begin;

-- ============================================================================
-- 1. Bulletproof Permission Helper: can_manage_attendance_slot
-- Allows app owner, admins, assigned teachers (TCA), or teacher directory matches.
-- ============================================================================
create or replace function classmate.can_manage_attendance_slot(target_slot uuid)
returns boolean
language sql stable security definer set search_path='' as $$
  select current_user in ('postgres', 'supabase_admin') or classmate.is_owner() or exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    left join classmate.teacher_course_assignments tca on tca.semester_course_id = sc.id and tca.teacher_id = auth.uid() and tca.active
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.profiles p on p.id = auth.uid()
    where rs.id = target_slot
      and (
        p.role = 'admin'
        or tca.id is not null
        or td.profile_id = auth.uid()
        or lower(td.email) = lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      )
  );
$$;
revoke all on function classmate.can_manage_attendance_slot(uuid) from public, anon;
grant execute on function classmate.can_manage_attendance_slot(uuid) to authenticated;

-- ============================================================================
-- 2. Bulletproof start_attendance_session
-- Ensures session is active, secret is never empty, and reuses today's session cleanly.
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
    -- Ensure session secret is never empty
    if existing_sess.session_secret is null or trim(existing_sess.session_secret) = '' then
      existing_sess.session_secret := coalesce(nullif(trim(client_secret), ''), encode(extensions.gen_random_bytes(24), 'hex'));
      update classmate.attendance_sessions
      set session_secret = existing_sess.session_secret
      where id = existing_sess.id;
    end if;

    -- Reopen session if currently closed
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

  sec := coalesce(nullif(trim(client_secret), ''), encode(extensions.gen_random_bytes(24), 'hex'));

  insert into classmate.attendance_sessions (routine_slot_id, started_by, session_secret, status)
  values (target_slot, coalesce(auth.uid(), (select id from classmate.profiles where role = 'admin' limit 1)), sec, 'open')
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
revoke all on function classmate.start_attendance_session(uuid, text) from public, anon;
grant execute on function classmate.start_attendance_session(uuid, text) to authenticated;

-- ============================================================================
-- 3. Bulletproof mark_attendance
-- - Strict dynamic rotating QR verification (within 45s of live server time)
-- - Rejects stale/screenshot QR codes with 22023
-- - Does not hard-fail outside nominal schedule (allows early/demo/test sessions)
-- - Flags late if scanned > 15m after start time
-- - Handles concurrent/repeat scans cleanly without throwing duplicates
-- ============================================================================
create or replace function classmate.mark_attendance(
  target_session uuid,
  qr_token text,
  time_window bigint
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess record;
  current_win bigint;
  expected_token text;
  caller_student_id uuid := auth.uid();
  rec_id uuid;
  is_late boolean := false;
  scan_time time;
begin
  select s.*, rs.start_time, rs.end_time, rs.semester_course_id
  into sess
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  where s.id = target_session;

  if not found then
    raise exception 'Attendance session not found' using errcode='42704';
  end if;

  if sess.status <> 'open' then
    raise exception 'Attendance session is closed' using errcode='22023';
  end if;

  -- Dynamic rotating QR check (15-second rotation window)
  -- Allow up to 3 windows drift (+- 45 seconds) for network transmission and camera latency
  current_win := extract(epoch from now())::bigint / 15;
  if abs(current_win - time_window) > 3 then
    raise exception 'Invalid or expired QR code' using errcode='22023';
  end if;

  -- Verify HMAC-SHA256 signature
  expected_token := encode(extensions.hmac(time_window::text, sess.session_secret, 'sha256'), 'hex');
  if lower(trim(qr_token)) <> lower(trim(expected_token)) then
    raise exception 'Invalid or expired QR code' using errcode='22023';
  end if;

  -- Calculate if scan is late (> 15 minutes after class start)
  scan_time := ((to_timestamp(time_window * 15) at time zone 'Asia/Dhaka')::time);
  if sess.start_time is not null and scan_time > (sess.start_time + interval '15 minutes') then
    is_late := true;
  end if;

  -- Verify student belongs to this batch (or is admin/owner)
  if not exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.profiles p on p.id = caller_student_id
    where rs.id = sess.routine_slot_id and p.batch_id = s.batch_id and p.verification_status = 'active'
  ) and not (classmate.is_owner() or exists (select 1 from classmate.profiles where id = caller_student_id and role = 'admin')) then
    raise exception 'You are not enrolled in this batch' using errcode='42501';
  end if;

  insert into classmate.attendance_records(
    session_id, student_id, marked_at, synced_at, method, confidence
  ) values (
    target_session, caller_student_id,
    to_timestamp(time_window * 15),
    now(),
    'qr',
    case when is_late then 'late' else 'normal' end
  )
  on conflict (session_id, student_id) do update set
    synced_at = now()
  returning id into rec_id;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', case when is_late then 'late_recorded' else 'present' end
  );
end $$;
-- ============================================================================
-- 4. active_attendance_sessions with management query authorization
-- ============================================================================
create or replace function classmate.active_attendance_sessions(target_batch uuid)
returns table(
  session_id uuid,
  routine_slot_id uuid,
  semester_course_id uuid,
  started_at timestamptz,
  status text,
  present_count bigint
)
language sql stable security definer set search_path='' as $$
  select
    s.id as session_id,
    s.routine_slot_id,
    rs.semester_course_id,
    s.started_at,
    s.status,
    count(r.id) as present_count
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.semesters sem on sem.id = sc.semester_id
  left join classmate.attendance_records r on r.session_id = s.id
  where sem.batch_id = target_batch
    and s.status = 'open'
    and (current_user in ('postgres', 'supabase_admin') or classmate.can_read_batch(target_batch))
  group by s.id, s.routine_slot_id, rs.semester_course_id, s.started_at, s.status;
$$;
revoke all on function classmate.active_attendance_sessions(uuid) from public, anon;
grant execute on function classmate.active_attendance_sessions(uuid) to authenticated;

commit;
