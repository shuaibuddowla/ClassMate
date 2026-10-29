-- ============================================================================
-- Migration: 202610270001_rfid_hardware_endpoint_and_session_binding.sql
-- Description:
-- 1. Ensure start_attendance_session binds ESP32-READER-01 device automatically
-- 2. Ensure close_attendance_session unlinks ESP32-READER-01 device automatically
-- 3. Restore is_cr permission to update_attendance_devices policy
-- 4. Grant anon and authenticated access to process_rfid_scan & get_device_active_session
-- ============================================================================

begin;

-- 1. Restore CR and Admin permissions on attendance_devices
drop policy if exists update_attendance_devices on classmate.attendance_devices;
create policy update_attendance_devices on classmate.attendance_devices
  for all to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and (role in ('admin', 'teacher') or is_cr = true)
    )
  );

-- 2. Update start_attendance_session to automatically link ESP32-READER-01
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

    -- Bind hardware device to active session
    update classmate.attendance_devices
    set active_session_id = existing_sess.id, last_heartbeat = now()
    where id = 'ESP32-READER-01';

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

  -- Automatically bind default hardware reader if available
  update classmate.attendance_devices
  set active_session_id = sess_id, last_heartbeat = now()
  where id = 'ESP32-READER-01';

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

-- 3. Update close_attendance_session to unlink attendance device
create or replace function classmate.close_attendance_session(target_session uuid)
returns void
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found'; end if;

  if not (
    classmate.can_manage_attendance_slot(sess.routine_slot_id)
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
    or sess.started_by = auth.uid()
  ) then
    raise exception 'Unauthorized' using errcode='42501';
  end if;

  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where id = target_session;

  -- Unlink hardware device
  update classmate.attendance_devices
  set active_session_id = null
  where active_session_id = target_session;
end $$;

-- 4. Ensure permissions for hardware scanner RPCs
grant usage on schema classmate to anon, authenticated, service_role;
grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;
grant execute on function classmate.get_device_active_session(text) to anon, authenticated, service_role;

commit;
