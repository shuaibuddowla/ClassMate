-- ============================================================================
-- Migration: 202610130003_attendance_no_late_penalty.sql
-- Description:
-- Attendance can be taken anytime in between the class start and end time.
-- Remove automatic "late" flagging. Any student scanning during an open session
-- is marked as "present" (confidence = 'normal').
-- Also retroactively normalizes all existing 'late' records to 'normal'.
-- ============================================================================

begin;

-- 1. Retroactively update past 'late' records to 'normal'
update classmate.attendance_records
set confidence = 'normal'
where confidence = 'late';

-- 2. Update mark_attendance to strictly record 'present' / 'normal'
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
    'normal'
  )
  on conflict (session_id, student_id) do update set
    synced_at = now()
  returning id into rec_id;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', 'present'
  );
end $$;

revoke all on function classmate.mark_attendance(uuid, text, bigint) from public, anon;
grant execute on function classmate.mark_attendance(uuid, text, bigint) to authenticated;

commit;
