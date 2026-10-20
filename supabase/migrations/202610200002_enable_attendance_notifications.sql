begin;

-- ============================================================================
-- Migration: 202610200002_enable_attendance_notifications.sql
-- Enables push notifications for attendance sessions (attendance_open)
-- 1. Updates classmate.notification_recipients to deliver to enrolled students & admin students
-- 2. Updates classmate.notification_device_allowed to authorize attendance devices
-- 3. Adds classmate.notify_attendance_session(target_session uuid) for instant/manual notification
-- ============================================================================

-- 1. Update notification recipients for attendance_open
create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language sql stable security definer set search_path='' as $$
  select * from classmate.academic_notification_recipients(target_event)
  where exists (
    select 1 from classmate.notification_outbox e
    where e.id = target_event and e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update')
  )
  union select distinct p.id, dt.fcm_token
  from classmate.notification_outbox e
  join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
  join classmate.profiles p on p.verification_status = 'active'
  join classmate.device_tokens dt on dt.profile_id = p.id
  where e.id = target_event and e.kind = 'attendance_open'
    and p.role in ('student', 'admin') and p.batch_id = b.id and p.department_id = b.department_id
  union select p.id, d.fcm_token
  from classmate.notification_outbox e
  join classmate.profiles p on p.verification_status = 'active'
  join classmate.device_tokens d on d.profile_id = p.id
  where e.id = target_event and e.kind = 'blood_request' and classmate.blood_broadcast_allowed(e.record_id, p.id)
  union select p.id, d.fcm_token
  from classmate.notification_outbox e
  join classmate.batch_fund_transactions t on t.id = e.record_id
  join classmate.profiles p on p.id = t.student_profile_id and p.verification_status = 'active'
  join classmate.device_tokens d on d.profile_id = p.id
  where e.id = target_event
    and e.kind in ('batch_fund_deposit', 'batch_fund_update');
$$;

revoke all on function classmate.notification_recipients(uuid) from public, anon, authenticated;
grant execute on function classmate.notification_recipients(uuid) to service_role;

-- 2. Update notification device authorization for attendance_open
create or replace function classmate.notification_device_allowed(target_event uuid, target_profile uuid, target_hash text)
returns boolean
language sql stable security definer set search_path='' as $$
  select (
    exists (
      select 1 from classmate.notification_outbox e
      where e.id = target_event and e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update')
    ) and classmate.academic_notification_device_allowed(target_event, target_profile, target_hash)
  ) or exists (
    select 1 from classmate.notification_outbox e
    join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    join classmate.profiles p on p.id = target_profile and p.verification_status = 'active'
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event and e.kind = 'attendance_open'
      and p.role in ('student', 'admin') and p.batch_id = b.id and p.department_id = b.department_id
  ) or exists (
    select 1 from classmate.notification_outbox e
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event and e.kind = 'blood_request' and classmate.blood_broadcast_allowed(e.record_id, target_profile)
  ) or exists (
    select 1 from classmate.notification_outbox e
    join classmate.batch_fund_transactions t on t.id = e.record_id
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event
      and e.kind in ('batch_fund_deposit', 'batch_fund_update')
      and t.student_profile_id = target_profile
  );
$$;

revoke all on function classmate.notification_device_allowed(uuid, uuid, text) from public, anon, authenticated;
grant execute on function classmate.notification_device_allowed(uuid, uuid, text) to service_role;

-- 3. Notify / re-broadcast attendance session to batch students
create or replace function classmate.notify_attendance_session(target_session uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess record;
  v_batch uuid;
  v_dept uuid;
  v_outbox_id uuid;
  v_recipient_count integer := 0;
begin
  select s.id, s.routine_slot_id, s.status, s.started_by,
         sc.semester_id, sem.batch_id, b.department_id, c.course_title
  into sess
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters sem on sem.id = sc.semester_id
  join classmate.batches b on b.id = sem.batch_id
  where s.id = target_session;

  if not found then
    raise exception 'Attendance session not found' using errcode = 'P0002';
  end if;

  if sess.status <> 'open' then
    raise exception 'Cannot notify for a closed attendance session' using errcode = '22023';
  end if;

  if not (
    sess.started_by = auth.uid()
    or classmate.can_manage_attendance_slot(sess.routine_slot_id)
  ) then
    raise exception 'Unauthorized to notify for this attendance session' using errcode = '42501';
  end if;

  v_batch := sess.batch_id;
  v_dept := sess.department_id;

  -- Upsert outbox event
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label
  ) values (
    'attendance_open', sess.id, v_batch, v_dept, classmate.routing_label(v_batch)
  )
  on conflict (kind, record_id) do update set
    delivered_at = null,
    jobs_prepared = false,
    attempts = 0,
    created_at = now(),
    processing_until = null,
    last_error = null
  returning id into v_outbox_id;

  -- Clear previous jobs for this event to allow fresh delivery
  delete from classmate.notification_jobs where event_id = v_outbox_id;
  delete from classmate.notification_deliveries where outbox_id = v_outbox_id;

  -- Request background dispatch
  perform classmate.request_notification_dispatch();

  -- Count prospective recipients
  select count(*) into v_recipient_count
  from classmate.notification_recipients(v_outbox_id);

  return jsonb_build_object(
    'success', true,
    'session_id', sess.id,
    'event_id', v_outbox_id,
    'recipients', v_recipient_count,
    'course_title', sess.course_title
  );
end $$;

revoke all on function classmate.notify_attendance_session(uuid) from public, anon;
grant execute on function classmate.notify_attendance_session(uuid) to authenticated;

commit;
