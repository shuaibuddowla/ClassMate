-- ============================================================================
-- Migration: 202610270004_rfid_scan_already_present.sql
-- Description:
-- Differentiate first tap (CHECK_IN) and duplicate/second tap (ALREADY_PRESENT)
-- so hardware OLED screen shows cross mark with "ALREADY PRESENTED" and blinks red LED twice.
-- ============================================================================

begin;

create or replace function classmate.process_rfid_scan(
  p_card_uid text,
  p_device_id text default 'ESP32-READER-01'
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  norm_card text;
  dev record;
  sess record;
  stu record;
  v_course_code text;
  v_course_title text;
  v_record_id uuid;
begin
  norm_card := upper(trim(p_card_uid));
  if norm_card = '' or norm_card is null then
    return jsonb_build_object('status', 'INVALID', 'message', 'Card UID missing');
  end if;

  select * into dev from classmate.attendance_devices where id = p_device_id;
  if not found or dev.active_session_id is null then
    return jsonb_build_object('status', 'NO_SESSION', 'message', 'Device is not linked to any active session');
  end if;

  select s.id, s.routine_slot_id, s.semester_course_id, s.status, s.batch_id
  into sess
  from classmate.attendance_sessions s
  where s.id = dev.active_session_id;

  if not found or sess.status <> 'open' then
    update classmate.attendance_devices set active_session_id = null where id = p_device_id;
    return jsonb_build_object('status', 'NO_SESSION', 'message', 'Active session has ended or is closed');
  end if;

  -- Course details
  select coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  where sc.id = coalesce(sess.semester_course_id, (select semester_course_id from classmate.routine_slots where id = sess.routine_slot_id));

  -- Lookup student by RFID Card UID
  select id, full_name, student_id, batch_id, verification_status
  into stu
  from classmate.profiles
  where upper(rfid_card_uid) = norm_card;

  if not found then
    -- Log unregistered scan for instant web triage
    insert into classmate.unregistered_rfid_scans (card_uid, device_id)
    values (norm_card, p_device_id);

    return jsonb_build_object(
      'status', 'UNREGISTERED',
      'card_uid', norm_card,
      'device_id', p_device_id,
      'course_code', coalesce(v_course_code, 'CLASS'),
      'message', 'Card not registered to any student'
    );
  end if;

  -- Strict Batch Isolation: Student MUST belong to session batch
  if sess.batch_id is not null and stu.batch_id is not null and stu.batch_id <> sess.batch_id then
    return jsonb_build_object(
      'status', 'WRONG_BATCH',
      'user_name', stu.full_name,
      'identifier', coalesce(stu.student_id, 'STUDENT'),
      'course_code', coalesce(v_course_code, 'CLASS'),
      'message', 'Student belongs to a different batch'
    );
  end if;

  -- Check if already marked present in this session (Double tap)
  select id into v_record_id
  from classmate.attendance_records
  where session_id = sess.id and student_id = stu.id;

  if found then
    return jsonb_build_object(
      'status', 'ALREADY_PRESENT',
      'user_name', stu.full_name,
      'identifier', coalesce(stu.student_id, 'STUDENT'),
      'course_code', coalesce(v_course_code, 'CLASS'),
      'course_title', coalesce(v_course_title, 'Class Attendance'),
      'marked_at', (select marked_at from classmate.attendance_records where id = v_record_id),
      'session_id', sess.id,
      'message', 'Already Presented'
    );
  end if;

  -- Fresh attendance tap: record attendance
  insert into classmate.attendance_records (
    session_id, student_id, method, confidence
  ) values (
    sess.id, stu.id, 'rfid', 'normal'
  )
  on conflict (session_id, student_id) do update set
    synced_at = now()
  returning id into v_record_id;

  -- Queue student confirmation notification
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label
  ) values (
    'attendance_marked', v_record_id, stu.batch_id,
    (select department_id from classmate.profiles where id = stu.id),
    'student_' || stu.id::text
  ) on conflict do nothing;

  perform classmate.request_notification_dispatch();

  return jsonb_build_object(
    'status', 'CHECK_IN',
    'user_name', stu.full_name,
    'identifier', coalesce(stu.student_id, 'STUDENT'),
    'course_code', coalesce(v_course_code, 'CLASS'),
    'course_title', coalesce(v_course_title, 'Class Attendance'),
    'marked_at', now(),
    'session_id', sess.id
  );
end $$;

grant usage on schema classmate to anon, authenticated, service_role;
grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;

commit;
