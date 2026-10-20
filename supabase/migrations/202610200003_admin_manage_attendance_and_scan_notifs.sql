begin;

-- ============================================================================
-- Migration: 202610200003_admin_manage_attendance_and_scan_notifs.sql
-- 1. Support ad-hoc schedule-independent attendance sessions
-- 2. Device polling heartbeat & auto-sleep RPC
-- 3. Card registration & strict batch isolation in RFID scan
-- 4. Single-student push notifications upon scan
-- 5. Filter student_my_attendance to only courses with held sessions
-- ============================================================================

-- 1. Schema Updates: Allow nullable routine_slot_id, add semester_course_id and batch_id
alter table classmate.attendance_sessions alter column routine_slot_id drop not null;
alter table classmate.attendance_sessions add column if not exists semester_course_id uuid references classmate.semester_courses(id) on delete cascade;
alter table classmate.attendance_sessions add column if not exists batch_id uuid references classmate.batches(id) on delete cascade;

create index if not exists attendance_sessions_course_idx
  on classmate.attendance_sessions(semester_course_id, status);

create index if not exists attendance_sessions_batch_idx
  on classmate.attendance_sessions(batch_id, status);

-- 2. Backfill existing sessions batch_id and semester_course_id from routine_slots
update classmate.attendance_sessions s
set semester_course_id = rs.semester_course_id,
    batch_id = sem.batch_id
from classmate.routine_slots rs
join classmate.semester_courses sc on sc.id = rs.semester_course_id
join classmate.semesters sem on sem.id = sc.semester_id
where s.routine_slot_id = rs.id
  and (s.semester_course_id is null or s.batch_id is null);

-- 3. Device Status Heartbeat RPC (Used by ESP32 GET /api/scan?device_id=...)
create or replace function classmate.get_device_active_session(target_device text)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  dev record;
  sess record;
begin
  select * into dev from classmate.attendance_devices where id = target_device;
  if not found then
    return jsonb_build_object('status', 'SLEEP', 'message', 'Device not registered');
  end if;

  -- Update heartbeat
  update classmate.attendance_devices set last_heartbeat = now() where id = target_device;

  if dev.active_session_id is null then
    return jsonb_build_object('status', 'SLEEP');
  end if;

  select s.id, s.status, s.started_at,
         coalesce(sc.code_override, c.course_code) as course_code,
         coalesce(sc.title_override, c.course_title) as course_title,
         s.batch_id
  into sess
  from classmate.attendance_sessions s
  left join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
  join classmate.courses c on c.id = sc.course_id
  where s.id = dev.active_session_id;

  if not found or sess.status <> 'open' then
    -- Unlink closed session
    update classmate.attendance_devices set active_session_id = null where id = target_device;
    return jsonb_build_object('status', 'SLEEP');
  end if;

  return jsonb_build_object(
    'status', 'ACTIVE',
    'session_id', sess.id,
    'course_code', coalesce(sess.course_code, 'CLASS'),
    'course_title', coalesce(sess.course_title, 'Class Attendance'),
    'started_at', sess.started_at
  );
end $$;

revoke all on function classmate.get_device_active_session(text) from public, anon;
grant execute on function classmate.get_device_active_session(text) to authenticated, service_role;

-- 4. Start Ad-Hoc Attendance Session (Schedule-Independent)
create or replace function classmate.start_adhoc_attendance_session(
  target_semester_course uuid,
  target_device text default 'ESP32-READER-01'
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid;
  v_dept uuid;
  v_course_code text;
  v_course_title text;
  sess_id uuid;
  sec text;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  -- Check permissions (Admin, Owner, or assigned teacher)
  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and role = 'admin')
    or exists (
      select 1 from classmate.teacher_course_assignments tca
      where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
    )
  ) then
    raise exception 'Unauthorized to start attendance session for this course' using errcode='42501';
  end if;

  select sem.batch_id, b.department_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch, v_dept, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters sem on sem.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  join classmate.batches b on b.id = sem.batch_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  -- Close any existing open session for this course to avoid stale duplicates
  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where semester_course_id = target_semester_course and status = 'open';

  sec := encode(extensions.gen_random_bytes(16), 'hex');

  insert into classmate.attendance_sessions (
    routine_slot_id, semester_course_id, batch_id, started_by, session_secret, status
  ) values (
    null, target_semester_course, v_batch, caller_id, sec, 'open'
  )
  returning id into sess_id;

  -- Automatically bind hardware device if specified
  if target_device is not null then
    update classmate.attendance_devices
    set active_session_id = sess_id, last_heartbeat = now()
    where id = target_device;
  end if;

  -- Queue attendance open notification for batch
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label
  ) values (
    'attendance_open', sess_id, v_batch, v_dept, classmate.routing_label(v_batch)
  ) on conflict (kind, record_id) do update set
    delivered_at = null,
    jobs_prepared = false,
    attempts = 0,
    created_at = now();

  perform classmate.request_notification_dispatch();

  return jsonb_build_object(
    'session_id', sess_id,
    'session_secret', sec,
    'status', 'open',
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch
  );
end $$;

revoke all on function classmate.start_adhoc_attendance_session(uuid, text) from public, anon;
grant execute on function classmate.start_adhoc_attendance_session(uuid, text) to authenticated;

-- 5. Close Ad-Hoc Attendance Session
create or replace function classmate.close_adhoc_attendance_session(target_session uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  sess record;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;
  select s.id, s.status, s.started_by, s.batch_id
  into sess
  from classmate.attendance_sessions s
  where s.id = target_session;

  if not found then
    raise exception 'Attendance session not found' using errcode='P0002';
  end if;

  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and role = 'admin')
    or sess.started_by = caller_id
  ) then
    raise exception 'Unauthorized to close attendance session' using errcode='42501';
  end if;

  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where id = target_session;

  -- Unlink device
  update classmate.attendance_devices
  set active_session_id = null
  where active_session_id = target_session;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', 'closed'
  );
end $$;

revoke all on function classmate.close_adhoc_attendance_session(uuid) from public, anon;
grant execute on function classmate.close_adhoc_attendance_session(uuid) to authenticated;

-- 6. Atomic RFID Scan Processor with Strict Batch Isolation & Push Notification
drop function if exists classmate.process_rfid_scan(text, text);
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
  is_new_mark boolean := false;
  v_outbox_id uuid;
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

  -- Record attendance idempotently
  insert into classmate.attendance_records (
    session_id, student_id, method, confidence
  ) values (
    sess.id, stu.id, 'rfid', 'normal'
  )
  on conflict (session_id, student_id) do update set
    synced_at = now()
  returning id, (xmax = 0) into v_record_id, is_new_mark;

  -- If this is a fresh attendance tap, queue student confirmation notification
  if is_new_mark then
    insert into classmate.notification_outbox(
      kind, record_id, batch_id, department_id, routing_label
    ) values (
      'attendance_marked', v_record_id, stu.batch_id,
      (select department_id from classmate.profiles where id = stu.id),
      'student_' || stu.id::text
    ) on conflict do nothing;

    perform classmate.request_notification_dispatch();
  end if;

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

revoke all on function classmate.process_rfid_scan(text, text) from public, anon;
grant execute on function classmate.process_rfid_scan(text, text) to authenticated, service_role;

-- 7. Update Notification Recipients & Authorization for attendance_marked
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
  union select ar.student_id, dt.fcm_token
  from classmate.notification_outbox e
  join classmate.attendance_records ar on ar.id = e.record_id
  join classmate.profiles p on p.id = ar.student_id and p.verification_status = 'active'
  join classmate.device_tokens dt on dt.profile_id = p.id
  where e.id = target_event and e.kind = 'attendance_marked'
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
    join classmate.attendance_records ar on ar.id = e.record_id
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event and e.kind = 'attendance_marked' and ar.student_id = target_profile
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

-- 8. Academic Notification Job Context for attendance_marked
create or replace function classmate.academic_notification_job_context(target_event uuid, target_hash text, target_lease uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  content jsonb;
  sess record;
  mark_info record;
begin
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' and lease_until>now();
  if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then return null; end if;
  select * into e from classmate.notification_outbox where id=target_event;

  -- Attendance Marked Confirmation
  if e.kind = 'attendance_marked' then
    select ar.id, coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into mark_info
    from classmate.attendance_records ar
    join classmate.attendance_sessions s on s.id = ar.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where ar.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id=j.profile_id),
      'title', 'Attendance Recorded',
      'body', 'Your attendance for ' || mark_info.course_title || ' was marked present in the app.',
      'kind', 'attendance_marked',
      'record_id', mark_info.id::text,
      'failures', j.failures
    );
  end if;

  -- Attendance Open Announcement
  if e.kind = 'attendance_open' then
    select s.id as session_id, s.routine_slot_id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into sess
    from classmate.attendance_sessions s
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where s.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id=j.profile_id)),
      'title', 'Attendance Open',
      'body', '📍 ' || coalesce(sess.course_title, 'Class') || ' attendance is open — tap to scan',
      'kind', 'attendance_open',
      'session_id', sess.session_id::text,
      'routine_slot_id', coalesce(sess.routine_slot_id::text, ''),
      'failures', j.failures
    );
  end if;

  case e.kind
    when 'notice' then select to_jsonb(n) into content from classmate.notices n where id=e.record_id and not silent;
    when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id=e.record_id and status='active';
    when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id=e.record_id;
    when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id=e.record_id;
    when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id=e.record_id;
    else return null;
  end case;
  if content is null or (e.kind<>'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind<>'semester' and content->>'department_id' is distinct from e.department_id::text))) then return null; end if;
  return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
    'batch_id',coalesce(e.batch_id,(select batch_id from classmate.profiles where id=j.profile_id)),
    'title',left(coalesce(content->>'title','ClassMate update'),200),'body',left(coalesce(content->>'body','New class update available'),240),
    'failures',j.failures,'version_code',content->>'version_code','version_name',content->>'version_name');
end $$;

revoke all on function classmate.academic_notification_job_context(uuid, text, uuid) from public, anon, authenticated;
grant execute on function classmate.academic_notification_job_context(uuid, text, uuid) to service_role;

-- 9. Student "My Attendance" RPC: Only show courses for which attendance was taken before
drop function if exists classmate.student_my_attendance(uuid);
create or replace function classmate.student_my_attendance(target_batch uuid default null)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid := target_batch;
  courses_list jsonb;
  total_held int := 0;
  total_attended int := 0;
begin
  if caller_id is null and current_user in ('postgres', 'supabase_admin') then
    select id into caller_id from classmate.profiles where role = 'admin' limit 1;
  end if;

  if not (current_user in ('postgres', 'supabase_admin') or classmate.is_active()) then
    raise exception 'Active profile required' using errcode='42501';
  end if;

  if v_batch is null then
    select batch_id into v_batch from classmate.profiles where id = caller_id;
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'semester_course_id', sc.id,
        'course_code', coalesce(sc.code_override, c.course_code),
        'course_title', coalesce(sc.title_override, c.course_title),
        'course_type', coalesce(sc.type_override, c.course_type),
        'credit', coalesce(sc.credit_override, c.credit, 3.0),
        'teacher_name', coalesce(td.full_name, 'Instructor'),
        'attendance_enabled', true,
        'classes_held', coalesce(c_stat.held, 0),
        'classes_attended', coalesce(c_stat.attended, 0),
        'percentage', case 
          when coalesce(c_stat.held, 0) > 0 
          then round((coalesce(c_stat.attended, 0)::numeric / c_stat.held::numeric) * 100, 1)
          else 0 end,
        'history', coalesce(c_stat.history, '[]'::jsonb)
      ) order by coalesce(sc.code_override, c.course_code) asc
    ),
    '[]'::jsonb
  )
  into courses_list
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters s on s.id = sc.semester_id
  left join classmate.teacher_directory td on td.id = sc.teacher_record_id
  left join lateral (
    select
      count(distinct sess.id) as held,
      count(distinct r.session_id) as attended,
      coalesce(
        jsonb_agg(
          jsonb_build_object(
            'session_id', sess.id,
            'date', (sess.started_at at time zone 'Asia/Dhaka')::date,
            'time', to_char((sess.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
            'room', coalesce(rs.room, 'Room 304'),
            'status', case when r.id is not null then 'present' else 'absent' end,
            'marked_at', r.marked_at
          ) order by sess.started_at desc
        ) filter (where sess.id is not null),
        '[]'::jsonb
      ) as history
    from classmate.attendance_sessions sess
    left join classmate.routine_slots rs on rs.id = sess.routine_slot_id
    left join classmate.attendance_records r on r.session_id = sess.id and r.student_id = caller_id
    where coalesce(sess.semester_course_id, rs.semester_course_id) = sc.id
  ) c_stat on true
  where s.batch_id = v_batch and s.status in ('active', 'not_started')
    and coalesce(c_stat.held, 0) > 0;

  select coalesce(sum((item->>'classes_held')::int), 0),
         coalesce(sum((item->>'classes_attended')::int), 0)
  into total_held, total_attended
  from jsonb_array_elements(courses_list) as item;

  return jsonb_build_object(
    'courses', courses_list,
    'total_classes_held', total_held,
    'total_classes_attended', total_attended,
    'overall_percentage', case 
      when total_held > 0 
      then round((total_attended::numeric / total_held::numeric) * 100, 1)
      else 0 end
  );
end $$;

revoke all on function classmate.student_my_attendance(uuid) from public, anon;
grant execute on function classmate.student_my_attendance(uuid) to authenticated;

-- 10. Course Attendance Matrix (Includes both scheduled and ad-hoc sessions)
create or replace function classmate.course_attendance_matrix(
  target_semester_course uuid
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  sessions_list jsonb;
  students_list jsonb;
begin
  if not is_admin and not exists (
    select 1 from classmate.profiles where id = caller_id and role = 'admin'
  ) and not exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
  ) then
    raise exception 'Unauthorized to view attendance matrix for this course' using errcode='42501';
  end if;

  select s.batch_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch_id, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  -- Sessions for this course (both scheduled and ad-hoc)
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'session_id', s.id,
        'started_at', s.started_at,
        'date', (s.started_at at time zone 'Asia/Dhaka')::date,
        'time', to_char((s.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
        'status', s.status,
        'room', coalesce(rs.room, 'Station')
      ) order by s.started_at asc
    ),
    '[]'::jsonb
  )
  into sessions_list
  from classmate.attendance_sessions s
  left join classmate.routine_slots rs on rs.id = s.routine_slot_id
  where coalesce(s.semester_course_id, rs.semester_course_id) = target_semester_course;

  -- Enrolled students in batch
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'records', coalesce(stu_records.rec_map, '{}'::jsonb),
        'present_count', coalesce(stu_records.pres_cnt, 0),
        'total_sessions', jsonb_array_length(sessions_list),
        'percentage', case 
          when jsonb_array_length(sessions_list) > 0 
          then round((coalesce(stu_records.pres_cnt, 0)::numeric / jsonb_array_length(sessions_list)::numeric) * 100, 1)
          else 0 end
      ) order by coalesce(p.student_id, p.full_name) asc
    ),
    '[]'::jsonb
  )
  into students_list
  from classmate.profiles p
  left join lateral (
    select
      jsonb_object_agg(r.session_id, 'present') as rec_map,
      count(r.id) as pres_cnt
    from classmate.attendance_records r
    join classmate.attendance_sessions s on s.id = r.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    where r.student_id = p.id
      and coalesce(s.semester_course_id, rs.semester_course_id) = target_semester_course
  ) stu_records on true
  where p.batch_id = v_batch_id and p.role in ('student', 'admin') and p.verification_status = 'active';

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'sessions', sessions_list,
    'students', students_list
  );
end $$;

revoke all on function classmate.course_attendance_matrix(uuid) from public, anon;
grant execute on function classmate.course_attendance_matrix(uuid) to authenticated;

-- 11. Admin Helper: Fetch Batch Students for Card Registration
create or replace function classmate.admin_batch_students(target_batch uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
begin
  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and (role = 'admin' or is_cr))
  ) then
    raise exception 'Unauthorized' using errcode='42501';
  end if;

  return coalesce((
    select jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'rfid_card_uid', p.rfid_card_uid
      ) order by coalesce(p.student_id, p.full_name) asc
    )
    from classmate.profiles p
    where p.batch_id = target_batch and p.role in ('student', 'admin') and p.verification_status = 'active'
  ), '[]'::jsonb);
end $$;

revoke all on function classmate.admin_batch_students(uuid) from public, anon;
grant execute on function classmate.admin_batch_students(uuid) to authenticated;

commit;
