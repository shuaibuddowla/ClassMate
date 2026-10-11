begin;

-- ============================================================================
-- 1. Database Capacity Recovery: Clean up runaway public.audit_log (240+ MB)
-- ============================================================================
truncate table public.audit_log;

-- Update write_audit_log to avoid recording duplicate updates if data hasn't changed
create or replace function public.write_audit_log()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  row_id uuid;
begin
  -- Skip logging UPDATE if the row data is identical
  if tg_op = 'UPDATE' and to_jsonb(old) = to_jsonb(new) then
    return new;
  end if;

  if tg_op = 'DELETE' then
    row_id := (to_jsonb(old) ->> 'id')::uuid;
  else
    row_id := (to_jsonb(new) ->> 'id')::uuid;
  end if;

  insert into public.audit_log (actor_id, action, entity_table, entity_id, old_data, new_data)
  values (
    public.current_profile_id(), lower(tg_op), tg_table_name, row_id,
    case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) end,
    case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) end
  );

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

-- ============================================================================
-- 2. Attendance Schema & Tables
-- ============================================================================
create table if not exists classmate.attendance_sessions (
  id uuid primary key default gen_random_uuid(),
  routine_slot_id uuid not null references classmate.routine_slots(id) on delete cascade,
  started_by uuid not null references classmate.profiles(id),
  session_secret text not null,
  started_at timestamptz not null default now(),
  closed_at timestamptz,
  status text not null default 'open' check (status in ('open', 'closed')),
  created_at timestamptz not null default now()
);

create table if not exists classmate.attendance_records (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references classmate.attendance_sessions(id) on delete cascade,
  student_id uuid not null references classmate.profiles(id),
  marked_at timestamptz not null default now(),
  synced_at timestamptz not null default now(),
  method text not null default 'qr' check (method in ('qr', 'rfid', 'manual')),
  confidence text not null default 'normal' check (confidence in ('normal', 'late', 'admin_override')),
  created_at timestamptz not null default now(),
  unique (session_id, student_id)
);

create index if not exists attendance_sessions_slot_idx
  on classmate.attendance_sessions(routine_slot_id, status);

create index if not exists attendance_records_session_idx
  on classmate.attendance_records(session_id);

create index if not exists attendance_records_student_idx
  on classmate.attendance_records(student_id);

alter table classmate.attendance_sessions enable row level security;
alter table classmate.attendance_records enable row level security;

-- ============================================================================
-- 3. Row Level Security Policies
-- ============================================================================
-- Sessions: visible to students enrolled in the batch and teachers/admins
drop policy if exists view_attendance_sessions on classmate.attendance_sessions;
create policy view_attendance_sessions on classmate.attendance_sessions
  for select to authenticated
  using (
    classmate.is_owner()
    or started_by = auth.uid()
    or exists (
      select 1 from classmate.routine_slots rs
      join classmate.semester_courses sc on sc.id = rs.semester_course_id
      join classmate.semesters s on s.id = sc.semester_id
      join classmate.profiles p on p.id = auth.uid()
      where rs.id = routine_slot_id and p.batch_id = s.batch_id and p.verification_status = 'active'
    )
    or exists (
      select 1 from classmate.routine_slots rs
      join classmate.teacher_course_assignments tca on tca.semester_course_id = rs.semester_course_id
      where rs.id = routine_slot_id and tca.teacher_id = auth.uid() and tca.active
    )
  );

-- Sessions: insert/update by assigned teacher or admin
drop policy if exists manage_attendance_sessions on classmate.attendance_sessions;
create policy manage_attendance_sessions on classmate.attendance_sessions
  for all to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.routine_slots rs
      join classmate.teacher_course_assignments tca on tca.semester_course_id = rs.semester_course_id
      where rs.id = routine_slot_id and tca.teacher_id = auth.uid() and tca.active
    )
  );

-- Records: visible to the student themselves, the session creator, course teachers, or admins
drop policy if exists view_attendance_records on classmate.attendance_records;
create policy view_attendance_records on classmate.attendance_records
  for select to authenticated
  using (
    classmate.is_owner()
    or student_id = auth.uid()
    or exists (
      select 1 from classmate.attendance_sessions s
      where s.id = session_id and s.started_by = auth.uid()
    )
    or exists (
      select 1 from classmate.attendance_sessions s
      join classmate.routine_slots rs on rs.id = s.routine_slot_id
      join classmate.teacher_course_assignments tca on tca.semester_course_id = rs.semester_course_id
      where s.id = session_id and tca.teacher_id = auth.uid() and tca.active
    )
  );

-- ============================================================================
-- 4. RPC Functions for Attendance Management
-- ============================================================================

-- Check if teacher can manage session for slot
create or replace function classmate.can_manage_attendance_slot(target_slot uuid)
returns boolean
language sql stable security definer set search_path='' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.routine_slots rs
    join classmate.teacher_course_assignments tca on tca.semester_course_id = rs.semester_course_id
    where rs.id = target_slot and tca.teacher_id = auth.uid() and tca.active
  );
$$;

-- Get live status for class card
create or replace function classmate.get_slot_attendance_status(target_slot uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
  pres_count int;
  tot_count int;
begin
  select * into sess
  from classmate.attendance_sessions
  where routine_slot_id = target_slot and status = 'open'
  order by started_at desc
  limit 1;

  if not found then
    return null;
  end if;

  select count(*) into pres_count
  from classmate.attendance_records
  where session_id = sess.id;

  select count(*) into tot_count
  from classmate.profiles p
  join classmate.semesters s on s.batch_id = p.batch_id
  join classmate.semester_courses sc on sc.semester_id = s.id
  join classmate.routine_slots rs on rs.semester_course_id = sc.id
  where rs.id = target_slot and p.verification_status = 'active' and p.role = 'student';

  return jsonb_build_object(
    'session_id', sess.id,
    'started_at', sess.started_at,
    'started_by', sess.started_by,
    'status', sess.status,
    'present_count', pres_count,
    'total_count', tot_count
  );
end $$;

-- Start attendance session
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
begin
  if not classmate.can_manage_attendance_slot(target_slot) then
    raise exception 'Unauthorized to start attendance for this routine slot' using errcode='42501';
  end if;

  -- Close any existing open session for this slot
  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where routine_slot_id = target_slot and status = 'open';

  sec := coalesce(nullif(trim(client_secret), ''), encode(gen_random_bytes(24), 'hex'));

  insert into classmate.attendance_sessions (routine_slot_id, started_by, session_secret, status)
  values (target_slot, auth.uid(), sec, 'open')
  returning id into sess_id;

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
    'status', 'open'
  );
end $$;

-- Mark attendance via QR scanning
create or replace function classmate.mark_attendance(
  target_session uuid,
  qr_token text,
  time_window bigint
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
  current_win bigint;
  expected_token text;
  caller_student_id uuid := auth.uid();
  rec_id uuid;
  is_late boolean := false;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then
    raise exception 'Attendance session not found' using errcode='42704';
  end if;

  -- Time window: 15-second epoch window
  current_win := extract(epoch from now())::bigint / 15;

  -- Allow current window or immediately preceding window (15s tolerance for clock skew)
  if abs(current_win - time_window) > 1 then
    is_late := true;
  end if;

  -- Verify HMAC hash
  expected_token := encode(extensions.hmac(time_window::text, sess.session_secret, 'sha256'), 'hex');
  if lower(trim(qr_token)) <> lower(trim(expected_token)) then
    raise exception 'Invalid or expired QR code' using errcode='22023';
  end if;

  -- Verify student belongs to this batch
  if not exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.profiles p on p.id = caller_student_id
    where rs.id = sess.routine_slot_id and p.batch_id = s.batch_id and p.verification_status = 'active'
  ) and not classmate.is_owner() then
    raise exception 'You are not enrolled in this batch' using errcode='42501';
  end if;

  insert into classmate.attendance_records(
    session_id, student_id, marked_at, synced_at, method, confidence
  ) values (
    target_session, caller_student_id,
    to_timestamp(time_window * 15),
    now(),
    'qr',
    case when is_late then 'late' else 'normal' end
  )
  on conflict (session_id, student_id) do nothing
  returning id into rec_id;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', case when is_late then 'late_recorded' else 'present' end
  );
end $$;

-- Manual override by teacher/admin
create or replace function classmate.manual_override_attendance(
  target_session uuid,
  target_student uuid,
  present boolean
)
returns void
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found'; end if;

  if not classmate.can_manage_attendance_slot(sess.routine_slot_id) then
    raise exception 'Unauthorized to manage attendance for this session' using errcode='42501';
  end if;

  if present then
    insert into classmate.attendance_records(session_id, student_id, method, confidence)
    values (target_session, target_student, 'manual', 'admin_override')
    on conflict (session_id, student_id) do update set confidence = 'admin_override', synced_at = now();
  else
    delete from classmate.attendance_records
    where session_id = target_session and student_id = target_student;
  end if;
end $$;

-- Close attendance session
create or replace function classmate.close_attendance_session(target_session uuid)
returns void
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found'; end if;

  if not classmate.can_manage_attendance_slot(sess.routine_slot_id) then
    raise exception 'Unauthorized' using errcode='42501';
  end if;

  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where id = target_session;
end $$;

-- Session summary with full roster (present + absent lists)
create or replace function classmate.get_session_summary(target_session uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
  roster jsonb;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found'; end if;

  select jsonb_agg(jsonb_build_object(
    'student_id', p.id,
    'full_name', p.full_name,
    'roll_number', p.student_id,
    'status', case when r.id is not null then 'present' else 'absent' end,
    'marked_at', r.marked_at,
    'method', coalesce(r.method, 'none'),
    'confidence', coalesce(r.confidence, 'none')
  ) order by p.student_id)
  into roster
  from classmate.routine_slots rs
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.profiles p on p.batch_id = s.batch_id and p.role = 'student' and p.verification_status = 'active'
  left join classmate.attendance_records r on r.session_id = target_session and r.student_id = p.id
  where rs.id = sess.routine_slot_id;

  return jsonb_build_object(
    'session_id', sess.id,
    'routine_slot_id', sess.routine_slot_id,
    'status', sess.status,
    'started_at', sess.started_at,
    'closed_at', sess.closed_at,
    'roster', coalesce(roster, '[]'::jsonb)
  );
end $$;

-- Enable Supabase Realtime for attendance records
do $$
begin
  if not exists (
    select 1 from pg_publication_tables 
    where pubname = 'supabase_realtime' and schemaname = 'classmate' and tablename = 'attendance_records'
  ) then
    alter publication supabase_realtime add table classmate.attendance_records;
  end if;
  if not exists (
    select 1 from pg_publication_tables 
    where pubname = 'supabase_realtime' and schemaname = 'classmate' and tablename = 'attendance_sessions'
  ) then
    alter publication supabase_realtime add table classmate.attendance_sessions;
  end if;
end $$;

-- Revoke/grant permissions
revoke all on function classmate.get_slot_attendance_status(uuid) from public, anon;
grant execute on function classmate.get_slot_attendance_status(uuid) to authenticated;

revoke all on function classmate.start_attendance_session(uuid, text) from public, anon;
grant execute on function classmate.start_attendance_session(uuid, text) to authenticated;

revoke all on function classmate.mark_attendance(uuid, text, bigint) from public, anon;
grant execute on function classmate.mark_attendance(uuid, text, bigint) to authenticated;

revoke all on function classmate.manual_override_attendance(uuid, uuid, boolean) from public, anon;
grant execute on function classmate.manual_override_attendance(uuid, uuid, boolean) to authenticated;

revoke all on function classmate.close_attendance_session(uuid) from public, anon;
grant execute on function classmate.close_attendance_session(uuid) to authenticated;

revoke all on function classmate.get_session_summary(uuid) from public, anon;
grant execute on function classmate.get_session_summary(uuid) to authenticated;

create or replace function classmate.academic_notification_job_context(target_event uuid,target_hash text,target_lease uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  content jsonb;
  sess record;
begin
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' and lease_until>now();
  if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
  select * into e from classmate.notification_outbox where id=target_event;
  if e.kind = 'attendance_open' then
    select s.id as session_id, s.routine_slot_id, c.course_title, c.course_code
    into sess
    from classmate.attendance_sessions s
    join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
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
      'routine_slot_id', sess.routine_slot_id::text,
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

notify pgrst, 'reload schema';
commit;
