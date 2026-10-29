-- Migration: 202610250002_rfid_index_and_device_security.sql
-- Description: Align functional index for RFID scans to upper(), scope CR device permissions, and revoke anon RPC access

-- 1. Create matching functional index on upper(rfid_card_uid) to accelerate RFID check-in queries
create unique index if not exists profiles_rfid_card_uid_upper_idx
  on classmate.profiles(upper(rfid_card_uid))
  where rfid_card_uid is not null;

-- 2. Restrict attendance hardware device modifications to admins and teachers
drop policy if exists update_attendance_devices on classmate.attendance_devices;
create policy update_attendance_devices on classmate.attendance_devices
  for all to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and role in ('admin', 'teacher')
    )
  );

-- 3. Revoke anonymous direct PostgREST execution on attendance scanner RPCs
revoke execute on function classmate.process_rfid_scan(text, text) from anon;
revoke execute on function classmate.get_device_active_session(text) from anon;
