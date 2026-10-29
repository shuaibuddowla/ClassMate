-- ============================================================================
-- Migration: 202610270005_device_session_roster_cache.sql
-- Description:
-- High-speed Edge Cache RPC for ESP32 RFID Attendance Station.
-- Returns the enrolled student card UIDs and initial attendance state for the active session,
-- allowing the hardware to perform instant (<50ms) local card verification and double-tap detection.
-- ============================================================================

begin;

create or replace function classmate.get_device_session_roster(p_device_id text)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  dev record;
  sess record;
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  v_students jsonb;
begin
  select * into dev from classmate.attendance_devices where id = p_device_id;
  if not found or dev.active_session_id is null then
    return jsonb_build_object('status', 'SLEEP');
  end if;

  select s.id, s.status, s.routine_slot_id, s.semester_course_id, s.batch_id
  into sess
  from classmate.attendance_sessions s
  where s.id = dev.active_session_id;

  if not found or sess.status <> 'open' then
    update classmate.attendance_devices set active_session_id = null where id = p_device_id;
    return jsonb_build_object('status', 'SLEEP');
  end if;

  -- Resolve batch_id
  v_batch_id := sess.batch_id;
  if v_batch_id is null and sess.semester_course_id is not null then
    select sem.batch_id into v_batch_id
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    where sc.id = sess.semester_course_id;
  end if;
  if v_batch_id is null and sess.routine_slot_id is not null then
    select sem.batch_id into v_batch_id
    from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    where rs.id = sess.routine_slot_id;
  end if;

  -- Course details
  select coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  where sc.id = coalesce(sess.semester_course_id, (select semester_course_id from classmate.routine_slots where id = sess.routine_slot_id));

  -- Collect students in this batch who have registered RFID cards
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'u', upper(trim(p.rfid_card_uid)),
        'i', coalesce(p.student_id, 'STUDENT'),
        'p', exists(select 1 from classmate.attendance_records ar where ar.session_id = sess.id and ar.student_id = p.id)
      ) order by p.student_id
    ),
    '[]'::jsonb
  )
  into v_students
  from classmate.profiles p
  where (v_batch_id is null or p.batch_id = v_batch_id)
    and p.rfid_card_uid is not null
    and trim(p.rfid_card_uid) <> '';

  return jsonb_build_object(
    'status', 'ACTIVE',
    'session_id', sess.id,
    'course_code', coalesce(v_course_code, 'CLASS'),
    'course_title', coalesce(v_course_title, 'Class Attendance'),
    'students', v_students
  );
end $$;

grant usage on schema classmate to anon, authenticated, service_role;
grant execute on function classmate.get_device_session_roster(text) to anon, authenticated, service_role;

commit;
