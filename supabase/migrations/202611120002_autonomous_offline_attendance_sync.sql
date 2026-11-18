-- ============================================================================
-- Migration: 202611120002_autonomous_offline_attendance_sync.sql
-- Autonomous Offline Attendance Sync & Dynamic Batch Resolver
-- Handles offline scans tagged with timestamps, even after class session closes.
-- ============================================================================

create or replace function classmate.process_offline_rfid_batch(
  p_device_id text,
  p_scans jsonb
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  scan_elem jsonb;
  norm_card text;
  v_timestamp timestamptz;
  v_course_code text;
  v_session_id uuid;
  stu record;
  dev record;
  sess record;
  v_record_id uuid;
  inserted_count int := 0;
  duplicate_count int := 0;
  unregistered_count int := 0;
  failed_count int := 0;
  results jsonb := '[]'::jsonb;
begin
  if p_scans is null or jsonb_array_length(p_scans) = 0 then
    return jsonb_build_object('status', 'EMPTY_BATCH', 'processed', 0);
  end if;

  select * into dev from classmate.attendance_devices where id = p_device_id;

  for scan_elem in select * from jsonb_array_elements(p_scans)
  loop
    -- 1. Extract and sanitize card UID
    norm_card := upper(trim(coalesce(scan_elem->>'card_uid', scan_elem->>'uid', '')));
    if norm_card = '' then
      failed_count := failed_count + 1;
      continue;
    end if;

    -- 2. Extract timestamp (default to now() if missing or invalid)
    begin
      if scan_elem ? 'timestamp' and (scan_elem->>'timestamp') ~ '^[0-9]+$' then
        v_timestamp := to_timestamp((scan_elem->>'timestamp')::bigint);
      elsif scan_elem ? 'scanned_at' then
        v_timestamp := (scan_elem->>'scanned_at')::timestamptz;
      else
        v_timestamp := now();
      end if;
    exception when others then
      v_timestamp := now();
    end;

    v_course_code := upper(trim(coalesce(scan_elem->>'course_code', scan_elem->>'course', '')));

    -- 3. Lookup student profile by RFID UID
    select id, full_name, student_id, batch_id, department_id, verification_status
    into stu
    from classmate.profiles
    where upper(rfid_card_uid) = norm_card;

    if not found then
      -- Log to unregistered scans for one-click admin enrollment
      insert into classmate.unregistered_rfid_scans (card_uid, device_id, created_at)
      values (norm_card, p_device_id, v_timestamp);
      unregistered_count := unregistered_count + 1;
      results := results || jsonb_build_object('uid', norm_card, 'status', 'UNREGISTERED');
      continue;
    end if;

    -- 4. Resolve the target attendance session
    v_session_id := null;

    -- Attempt A: Check if scan explicitly carries a valid session UUID
    if scan_elem ? 'session_id' and (scan_elem->>'session_id') ~* '^[0-9a-f-]{36}$' then
      select id into v_session_id
      from classmate.attendance_sessions
      where id = (scan_elem->>'session_id')::uuid;
    end if;

    -- Attempt B: Check if device currently has an active open session
    if v_session_id is null and dev.active_session_id is not null then
      select id into v_session_id
      from classmate.attendance_sessions
      where id = dev.active_session_id and (status = 'open' or closed_at >= v_timestamp - interval '2 hours');
    end if;

    -- Attempt C: Find a session for the student's batch that overlaps the tap timestamp
    if v_session_id is null and stu.batch_id is not null then
      select s.id into v_session_id
      from classmate.attendance_sessions s
      where s.batch_id = stu.batch_id
        and s.started_at <= (v_timestamp + interval '20 minutes')
        and (s.closed_at is null or s.closed_at >= (v_timestamp - interval '45 minutes'))
      order by abs(extract(epoch from (s.started_at - v_timestamp))) asc
      limit 1;
    end if;

    -- Attempt D: Match against timetable routine slot for that day/time
    if v_session_id is null and stu.batch_id is not null then
      declare
        v_day int := extract(dow from v_timestamp);
        v_time time := v_timestamp::time;
        v_slot record;
      begin
        select rs.id as slot_id, sc.id as semester_course_id, sem.batch_id
        into v_slot
        from classmate.routine_slots rs
        join classmate.semester_courses sc on sc.id = rs.semester_course_id
        join classmate.semesters sem on sem.id = sc.semester_id
        join classmate.courses c on c.id = sc.course_id
        where sem.batch_id = stu.batch_id
          and rs.day_of_week = v_day
          and (
            (rs.start_time - interval '20 minutes') <= v_time
            and (rs.end_time + interval '30 minutes') >= v_time
          )
        order by abs(extract(epoch from (rs.start_time - v_time))) asc
        limit 1;

        if v_slot.slot_id is not null then
          -- Find existing session for today or auto-create one for this completed offline class
          select id into v_session_id
          from classmate.attendance_sessions
          where routine_slot_id = v_slot.slot_id
            and started_at::date = v_timestamp::date;

          if v_session_id is null then
            insert into classmate.attendance_sessions (
              routine_slot_id, semester_course_id, batch_id,
              status, started_at, closed_at, created_at
            ) values (
              v_slot.slot_id, v_slot.semester_course_id, v_slot.batch_id,
              'closed', v_timestamp, (v_timestamp + interval '45 minutes'), v_timestamp
            ) returning id into v_session_id;
          end if;
        end if;
      end;
    end if;

    -- If still no session resolved, use the most recent session of the batch
    if v_session_id is null and stu.batch_id is not null then
      select id into v_session_id
      from classmate.attendance_sessions
      where batch_id = stu.batch_id
      order by created_at desc
      limit 1;
    end if;

    -- 5. Record the attendance if session was found
    if v_session_id is not null then
      insert into classmate.attendance_records (
        session_id, student_id, method, confidence, marked_at, synced_at
      ) values (
        v_session_id, stu.id, 'rfid_offline_sync', 'normal', v_timestamp, now()
      )
      on conflict (session_id, student_id) do nothing
      returning id into v_record_id;

      if v_record_id is not null then
        inserted_count := inserted_count + 1;
        -- Queue push notification for student
        insert into classmate.notification_outbox (
          kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
        ) values (
          'attendance_marked', v_record_id, stu.batch_id, stu.department_id,
          'student_' || stu.id::text, stu.id
        ) on conflict do nothing;

        results := results || jsonb_build_object(
          'uid', norm_card,
          'student_id', stu.student_id,
          'status', 'MARKED',
          'session_id', v_session_id
        );
      else
        duplicate_count := duplicate_count + 1;
        results := results || jsonb_build_object(
          'uid', norm_card,
          'student_id', stu.student_id,
          'status', 'ALREADY_PRESENT'
        );
      end if;
    else
      failed_count := failed_count + 1;
      results := results || jsonb_build_object('uid', norm_card, 'status', 'NO_SESSION_FOUND');
    end if;
  end loop;

  -- Trigger background push dispatch
  perform classmate.request_notification_dispatch();

  return jsonb_build_object(
    'status', 'BATCH_OK',
    'total', jsonb_array_length(p_scans),
    'inserted', inserted_count,
    'duplicates', duplicate_count,
    'unregistered', unregistered_count,
    'failed', failed_count,
    'results', results
  );
end $$;

revoke all on function classmate.process_offline_rfid_batch(text, jsonb) from public, anon;
grant execute on function classmate.process_offline_rfid_batch(text, jsonb) to authenticated, service_role;
