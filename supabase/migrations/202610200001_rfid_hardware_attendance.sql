-- ============================================================================
-- Migration: Hardware RFID Attendance Tracker (ESP32 + RC522)
-- Environment: Staging
-- ============================================================================

begin;

-- 1. Extend profiles with rfid_card_uid
alter table classmate.profiles
  add column if not exists rfid_card_uid text;

create unique index if not exists profiles_rfid_card_uid_lower_idx
  on classmate.profiles(lower(rfid_card_uid))
  where rfid_card_uid is not null;

-- 2. Hardware devices table
create table if not exists classmate.attendance_devices (
  id text primary key,
  label text not null,
  active_session_id uuid references classmate.attendance_sessions(id) on delete set null,
  last_heartbeat timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Seed default hardware scanner
insert into classmate.attendance_devices (id, label)
values ('ESP32-READER-01', 'Room 304 Portable Scanner (ESP32-READER-01)')
on conflict (id) do update set
  label = excluded.label,
  updated_at = now();

-- 3. Unregistered card triage log
create table if not exists classmate.unregistered_rfid_scans (
  id uuid primary key default gen_random_uuid(),
  card_uid text not null,
  device_id text not null,
  scanned_at timestamptz not null default now(),
  resolved boolean not null default false,
  resolved_at timestamptz,
  resolved_by uuid references classmate.profiles(id)
);

create index if not exists unregistered_rfid_card_idx 
  on classmate.unregistered_rfid_scans(lower(card_uid), resolved);

create index if not exists unregistered_rfid_scanned_at_idx 
  on classmate.unregistered_rfid_scans(scanned_at desc);

-- 4. Enable RLS
alter table classmate.attendance_devices enable row level security;
alter table classmate.unregistered_rfid_scans enable row level security;

-- Policies for attendance_devices:
drop policy if exists select_attendance_devices on classmate.attendance_devices;
create policy select_attendance_devices on classmate.attendance_devices
  for select to authenticated
  using (true);

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

-- Policies for unregistered_rfid_scans:
drop policy if exists select_unregistered_rfid_scans on classmate.unregistered_rfid_scans;
create policy select_unregistered_rfid_scans on classmate.unregistered_rfid_scans
  for select to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and (role in ('admin', 'teacher') or is_cr = true)
    )
  );

-- 5. RPC: Link device to active session
create or replace function classmate.link_attendance_device(
  p_device_id text,
  p_target_session uuid
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  v_sess record;
begin
  -- Validate permissions: must be admin, teacher, CR, or owner
  if not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and (role in ('admin', 'teacher') or is_cr = true)
    )
  ) then
    raise exception 'Permission denied to manage attendance devices' using errcode='42501';
  end if;

  if p_target_session is not null then
    select id, status into v_sess
    from classmate.attendance_sessions
    where id = p_target_session;

    if not found then
      raise exception 'Attendance session not found' using errcode='42704';
    end if;

    if v_sess.status <> 'open' then
      raise exception 'Attendance session is not open' using errcode='22023';
    end if;
  end if;

  insert into classmate.attendance_devices (id, label, active_session_id, updated_at)
  values (trim(p_device_id), 'Scanner ' || trim(p_device_id), p_target_session, now())
  on conflict (id) do update set
    active_session_id = p_target_session,
    updated_at = now();

  return jsonb_build_object(
    'success', true,
    'device_id', trim(p_device_id),
    'active_session_id', p_target_session
  );
end $$;

-- 6. RPC: Core RFID Scan Processor
create or replace function classmate.process_rfid_scan(
  p_card_uid text,
  p_device_id text
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

  -- 1. Identify student by card UID
  select id, student_id, full_name, batch_id
  into v_student
  from classmate.profiles
  where lower(replace(coalesce(rfid_card_uid, ''), ':', '')) = v_clean_uid;

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
    v_sess.id, v_student.id, now(), now(), 'rfid', 'normal'
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

-- 7. RPC: Enroll student card UID
create or replace function classmate.enroll_student_rfid(
  p_student_id uuid,
  p_card_uid text
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  v_clean_uid text := lower(trim(replace(p_card_uid, ':', '')));
  v_existing record;
begin
  if not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and (role in ('admin', 'teacher') or is_cr = true)
    )
  ) then
    raise exception 'Permission denied to enroll RFID cards' using errcode='42501';
  end if;

  if v_clean_uid = '' or v_clean_uid is null then
    raise exception 'Invalid card UID' using errcode='22023';
  end if;

  -- Check if already assigned to another student
  select id, full_name, student_id into v_existing
  from classmate.profiles
  where lower(replace(coalesce(rfid_card_uid, ''), ':', '')) = v_clean_uid
    and id <> p_student_id;

  if found then
    raise exception 'Card % is already enrolled to % (%)', v_clean_uid, v_existing.full_name, v_existing.student_id using errcode='23505';
  end if;

  update classmate.profiles
  set rfid_card_uid = v_clean_uid
  where id = p_student_id;

  -- Mark pending unregistered scans as resolved
  update classmate.unregistered_rfid_scans
  set resolved = true, resolved_at = now(), resolved_by = auth.uid()
  where lower(replace(card_uid, ':', '')) = v_clean_uid
    and resolved = false;

  return jsonb_build_object(
    'success', true,
    'student_id', p_student_id,
    'card_uid', v_clean_uid
  );
end $$;

-- 8. RPC: Fetch unregistered scans
create or replace function classmate.get_unregistered_rfid_scans()
returns table (
  id uuid,
  card_uid text,
  device_id text,
  scanned_at timestamptz
)
language sql security definer set search_path='' as $$
  select id, card_uid, device_id, scanned_at
  from classmate.unregistered_rfid_scans
  where resolved = false
  order by scanned_at desc
  limit 25;
$$;

-- Permissions
grant execute on function classmate.link_attendance_device(text, uuid) to authenticated;
grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;
grant execute on function classmate.enroll_student_rfid(uuid, text) to authenticated;
grant execute on function classmate.get_unregistered_rfid_scans() to authenticated;

commit;
