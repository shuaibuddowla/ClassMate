-- Migration: Enable direct verified Phone NFC HCE attendance in process_rfid_scan
begin;

drop function if exists classmate.process_rfid_scan(text, text);

create or replace function classmate.process_rfid_scan(
  p_card_uid text,
  p_device_id text default 'ESP32-READER-01'
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  v_clean_uid text := lower(trim(replace(p_card_uid, ':', '')));
  v_clean_device text := trim(p_device_id);
  v_student record;
  v_device record;
  v_sess record;
begin
  if v_clean_uid = '' or v_clean_uid is null then
    return jsonb_build_object(
      'status', 'ERROR',
      'user_name', 'Invalid Card',
      'identifier', 'N/A'
    );
  end if;

  -- 1. Identify student: Either by verified Phone NFC token (starts with nfc-) or by physical RFID card UID
  if v_clean_uid like 'nfc-%' then
    select id, student_id, full_name, batch_id
    into v_student
    from classmate.profiles
    where replace(id::text, '-', '') = replace(replace(v_clean_uid, 'nfc-', ''), ':', '');
  else
    select id, student_id, full_name, batch_id
    into v_student
    from classmate.profiles
    where lower(replace(coalesce(rfid_card_uid, ''), ':', '')) = v_clean_uid;
  end if;

  if not found then
    -- Log unresolved scan for triage
    insert into classmate.unregistered_rfid_scans (card_uid, device_id, scanned_at, resolved)
    values (v_clean_uid, v_clean_device, now(), false);

    return jsonb_build_object(
      'status', 'UNREGISTERED',
      'card_uid', v_clean_uid,
      'user_name', 'Unregistered Card',
      'identifier', v_clean_uid
    );
  end if;

  -- 2. Lookup device and currently linked session
  select id, active_session_id, label
  into v_device
  from classmate.attendance_devices
  where id = v_clean_device;

  if not found or v_device.active_session_id is null then
    return jsonb_build_object(
      'status', 'NO_SESSION',
      'user_name', v_student.full_name,
      'identifier', coalesce(v_student.student_id, 'N/A'),
      'message', 'Device is not linked to any active session'
    );
  end if;

  -- 3. Verify session is still open
  select s.id, s.status, s.routine_slot_id, c.course_code, c.course_title
  into v_sess
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.courses c on c.id = sc.course_id
  where s.id = v_device.active_session_id;

  if not found or v_sess.status <> 'open' then
    return jsonb_build_object(
      'status', 'SESSION_CLOSED',
      'user_name', v_student.full_name,
      'identifier', coalesce(v_student.student_id, 'N/A'),
      'message', 'Current session is closed'
    );
  end if;

  -- 4. Record attendance
  insert into classmate.attendance_records(
    session_id, student_id, marked_at, synced_at, method, confidence
  ) values (
    v_sess.id, v_student.id, now(), now(), 
    case when v_clean_uid like 'nfc-%' then 'phone_nfc' else 'rfid' end, 
    'normal'
  )
  on conflict (session_id, student_id) do update set
    synced_at = now();

  -- Update device heartbeat
  update classmate.attendance_devices
  set last_heartbeat = now()
  where id = v_clean_device;

  return jsonb_build_object(
    'status', 'CHECK_IN',
    'user_name', v_student.full_name,
    'identifier', coalesce(v_student.student_id, 'N/A'),
    'course_code', v_sess.course_code,
    'course_title', v_sess.course_title,
    'session_id', v_sess.id
  );
end $$;

grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;

commit;
