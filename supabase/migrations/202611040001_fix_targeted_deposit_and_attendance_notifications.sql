begin;

-- ============================================================================
-- Migration: 202611040001_fix_targeted_deposit_and_attendance_notifications.sql
-- Description:
-- Fix critical notification recipient leak.
-- In 202611020009_fix_targeted_ban_notifications, notification_recipients was
-- rewritten to fall through to batch-wide broadcast whenever recipient_profile_id
-- is null. However, batch_fund_deposit, batch_fund_update, and attendance_marked
-- had recipient_profile_id as null, causing every deposit and every student's
-- attendance punch to be broadcast to all 40+ students in the batch!
--
-- This migration fixes:
-- 1. notification_recipients: Strictly restricts single-user events (deposit,
--    fund update, attendance_marked, ban) to the single intended student.
--    Batch broadcast is ONLY permitted for explicit broadcast kinds.
-- 2. notification_device_allowed: Enforces individual matching for deposits
--    and attendance records so non-target devices are rejected.
-- 3. notification_job_context & academic_notification_job_context: Adds hard
--    safety guards dropping any job if profile_id != target student.
-- 4. record_batch_fund_transaction, record_batch_fund_transactions_bulk,
--    update_batch_fund_transaction, and process_rfid_scan:
--    Explicitly stamp recipient_profile_id onto notification_outbox.
-- 5. Cancels any stale un-delivered broadcast jobs for targeted events.
-- ============================================================================

-- 1. Fix notification_recipients: Strict routing by event kind & recipient
create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language plpgsql stable security definer set search_path = '' as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return; end if;

  -- A. Explicit single-recipient events (Bans, personal alerts, or any event with explicit recipient_profile_id)
  if e.recipient_profile_id is not null then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id and p.verification_status = 'active';
    return;
  end if;

  -- B. Batch Fund Deposit / Update (Fallback to transaction student_profile_id if recipient_profile_id was not set)
  if e.kind in ('batch_fund_deposit', 'batch_fund_update') then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.batch_fund_transactions t
      join classmate.profiles p on p.id = t.student_profile_id and p.verification_status = 'active'
      join classmate.device_tokens dt on dt.profile_id = p.id
      where t.id = e.record_id and t.student_profile_id is not null;
    return;
  end if;

  -- C. Attendance Marked Confirmation (Fallback to attendance record student_id if recipient_profile_id was not set)
  if e.kind = 'attendance_marked' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.attendance_records ar
      join classmate.profiles p on p.id = ar.student_id and p.verification_status = 'active'
      join classmate.device_tokens dt on dt.profile_id = p.id
      where ar.id = e.record_id and ar.student_id is not null;
    return;
  end if;

  -- D. Blood Request Broadcast (Targeted strictly to eligible donors matching criteria)
  if e.kind = 'blood_request' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.verification_status = 'active'
        and classmate.blood_broadcast_allowed(e.record_id, p.id);
    return;
  end if;

  -- E. Batch / Academic Event Broadcast (STRICTLY limited to explicit batch-wide broadcast kinds)
  if e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update', 'attendance_open') then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
      where (
        (p.role = 'student' and p.batch_id = b.id and p.department_id = b.department_id and p.verification_status = 'active')
        or (p.role = 'teacher' and p.verification_status = 'active' and exists (
          select 1 from classmate.teacher_course_assignments a
          join classmate.semester_courses sc on sc.id = a.semester_course_id
          join classmate.semesters s on s.id = sc.semester_id
          join classmate.teacher_allowlist t on t.email = p.email and t.department_id = b.department_id and t.active
          where a.teacher_id = p.id and a.active and s.batch_id = b.id and s.status = 'active'
            and (e.semester_course_id is null or sc.id = e.semester_course_id)
        ))
      );
    return;
  end if;

  -- Safe default: NEVER fall through to batch-wide broadcast for unknown or unstamped personal events!
  return;
end;
$$;

-- 2. Fix notification_device_allowed: Enforce target matching for individual events
create or replace function classmate.notification_device_allowed(
  target_event uuid,
  target_profile uuid,
  target_hash text
)
returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return false; end if;

  -- A. Explicit targeted recipient
  if e.recipient_profile_id is not null then
    return e.recipient_profile_id = target_profile and exists (
      select 1 from classmate.device_tokens d
      where d.token_hash = target_hash and d.profile_id = target_profile
    );
  end if;

  -- B. Batch fund deposit / update
  if e.kind in ('batch_fund_deposit', 'batch_fund_update') then
    return exists (
      select 1 from classmate.batch_fund_transactions t
      join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
      where t.id = e.record_id and t.student_profile_id = target_profile
    );
  end if;

  -- C. Attendance marked confirmation
  if e.kind = 'attendance_marked' then
    return exists (
      select 1 from classmate.attendance_records ar
      join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
      where ar.id = e.record_id and ar.student_id = target_profile
    );
  end if;

  -- D. Blood request
  if e.kind = 'blood_request' then
    return exists (
      select 1 from classmate.device_tokens d
      where d.token_hash = target_hash and d.profile_id = target_profile
    ) and classmate.blood_broadcast_allowed(e.record_id, target_profile);
  end if;

  -- E. Batch broadcast kinds
  if e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update', 'attendance_open') then
    return classmate.academic_notification_device_allowed(target_event, target_profile, target_hash);
  end if;

  return false;
end;
$$;

-- 3. Fix notification_job_context: Guard against cross-user deposit notification context
create or replace function classmate.notification_job_context(
  target_event uuid,
  target_hash text,
  target_lease uuid
)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  j classmate.notification_jobs%rowtype;
  r classmate.blood_requests%rowtype;
  t classmate.batch_fund_transactions%rowtype;
  e classmate.notification_outbox%rowtype;
  matched boolean;
  own_group text;
  amt_str text;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if not found then return null; end if;

  if e.kind in ('batch_fund_deposit', 'batch_fund_update') then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select * into t from classmate.batch_fund_transactions where id = e.record_id;
    if not found then return null; end if;

    -- Strict security guard: Never generate deposit payload for non-target user
    if t.student_profile_id is distinct from j.profile_id then
      return null;
    end if;
    if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
      return null;
    end if;

    if t.amount = trunc(t.amount) then
      amt_str := trim(to_char(t.amount, 'FM999,999,990'));
    else
      amt_str := trim(to_char(t.amount, 'FM999,999,990.00'));
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', e.batch_id,
      'recipient_id', j.profile_id,
      'kind', e.kind,
      'record_id', t.id::text,
      'title', case when e.kind = 'batch_fund_update' then 'Deposit Updated: ৳' || amt_str else 'Deposit Confirmed: ৳' || amt_str end,
      'body', case when e.kind = 'batch_fund_update'
        then 'Your deposit of ৳' || amt_str || ' for "' || left(t.title, 40) || '" has been updated.'
        else 'Your deposit of ৳' || amt_str || ' for "' || left(t.title, 40) || '" has been recorded.' end,
      'failures', j.failures
    );
  end if;

  if e.kind = 'blood_request' then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select r0.* into r from classmate.blood_requests r0 where r0.id = e.record_id;
    if not found then return null; end if;

    matched := classmate.blood_matching_alert(r.id, j.profile_id);
    select blood_group into own_group from classmate.profiles where id = j.profile_id;
    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
      'recipient_id', j.profile_id,
      'kind', e.kind,
      'record_id', r.id::text,
      'title', case when matched then 'Urgent: ' else '' end || r.blood_group || ' blood needed',
      'body', case when matched then 'Your profile lists ' || own_group || '. Can you help? Contact the attendant at ' || r.hospital || '.'
        else 'Know a friend or someone who might help? ' || r.units || ' unit(s) needed at ' || r.hospital || '. Open for contact details.' end,
      'blood_match', matched,
      'expires_at', r.needed_by,
      'failures', j.failures
    );
  end if;

  return classmate.academic_notification_job_context(target_event, target_hash, target_lease);
end $$;

-- 4. Fix academic_notification_job_context: Guard against cross-user attendance notification context
create or replace function classmate.academic_notification_job_context(
  target_event uuid,
  target_hash text,
  target_lease uuid
)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  content jsonb;
  sess record;
  mark_info record;
  notice_res record;
begin
  select * into j from classmate.notification_jobs
  where event_id = target_event and token_hash = target_hash and lease_id = target_lease
    and state = 'leased' and lease_until > now();
  if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
    return null;
  end if;

  select * into e from classmate.notification_outbox where id = target_event;

  -- 1. Attendance Marked Confirmation (Card tapped / attendance recorded)
  if e.kind = 'attendance_marked' then
    select ar.id, ar.student_id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into mark_info
    from classmate.attendance_records ar
    join classmate.attendance_sessions s on s.id = ar.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where ar.id = e.record_id;

    if not found then return null; end if;

    -- Strict security guard: Never generate attendance payload for non-target user
    if mark_info.student_id is distinct from j.profile_id then
      return null;
    end if;
    if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
      return null;
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
      'recipient_id', j.profile_id,
      'title', 'Attendance Recorded',
      'body', 'Your attendance for ' || mark_info.course_title || ' was marked present in the app.',
      'kind', 'attendance_marked',
      'record_id', mark_info.id::text,
      'failures', j.failures
    );
  end if;

  -- 2. Attendance Open Announcement (Punch your cards for attendance)
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
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
      'title', case when sess.course_code <> '' then 'Attendance Live · ' || sess.course_code else 'Attendance Live' end,
      'body', 'Punch your cards for ' || coalesce(sess.course_title, 'Class') || ' attendance.',
      'kind', 'attendance_open',
      'session_id', sess.session_id::text,
      'routine_slot_id', coalesce(sess.routine_slot_id::text, ''),
      'course_code', coalesce(sess.course_code, ''),
      'course_title', coalesce(sess.course_title, 'Class'),
      'route', 'my_attendance',
      'failures', j.failures
    );
  end if;

  -- 3. Dedicated Resource Notice (File upload or Google Drive link)
  if e.kind = 'notice' then
    select n.*, f.title as file_title,
           coalesce(sc.title_override, c.course_title) as course_title,
           coalesce(sc.code_override, c.course_code) as course_code
    into notice_res
    from classmate.notices n
    left join classmate.file_metadata f on f.id = n.resource_id
    left join classmate.semester_courses sc on sc.id = coalesce(n.semester_course_id, f.semester_course_id)
    left join classmate.courses c on c.id = sc.course_id
    where n.id = e.record_id and not n.silent;

    if not found then return null; end if;

    if notice_res.resource_id is not null and notice_res.file_title is not null then
      return jsonb_build_object(
        'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
        'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
        'title', 'New Resources',
        'body', case
                  when notice_res.course_title is not null and notice_res.course_code is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title || ' (' || notice_res.course_code || ')'
                  when notice_res.course_title is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title
                  else
                    notice_res.file_title || ' is now available'
                end,
        'kind', 'notice',
        'record_id', notice_res.id::text,
        'resource_id', notice_res.resource_id::text,
        'failures', j.failures
      );
    end if;

    content := to_jsonb(notice_res);
  else
    case e.kind
      when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id = e.record_id and status = 'active';
      when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id = e.record_id;
      when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id = e.record_id;
      when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id = e.record_id;
      else return null;
    end case;
  end if;

  if content is null or (e.kind <> 'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind <> 'semester' and content->>'department_id' is distinct from e.department_id::text))) then
    return null;
  end if;

  return jsonb_build_object(
    'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
    'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
    'title', left(coalesce(content->>'title', 'ClassMate update'), 200),
    'body', left(coalesce(content->>'body', 'New class update available'), 240),
    'failures', j.failures,
    'kind', e.kind,
    'record_id', e.record_id::text,
    'event_id', e.id::text
  );
end;
$$;

-- 5. Update record_batch_fund_transaction: Stamp recipient_profile_id on notification_outbox
create or replace function classmate.record_batch_fund_transaction(
  target_batch uuid,
  trans_type text,
  trans_amount numeric,
  trans_title text,
  trans_student_name text default null,
  trans_student_id text default null,
  trans_date date default current_date,
  trans_student_profile_id uuid default null
)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  new_id uuid;
  resolved_profile_id uuid := trans_student_profile_id;
  resolved_student_id text := nullif(trim(coalesce(trans_student_id, '')), '');
  resolved_student_name text := nullif(trim(coalesce(trans_student_name, '')), '');
  v_dept_id uuid;
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Only class representatives can record fund transactions' using errcode = '42501';
  end if;

  if trans_type not in ('inflow', 'outflow') then
    raise exception 'Transaction type must be inflow or outflow' using errcode = '22023';
  end if;

  if trans_amount <= 0 or trans_amount is null then
    raise exception 'Amount must be greater than zero' using errcode = '22023';
  end if;

  if trim(coalesce(trans_title, '')) = '' then
    raise exception 'Title is required' using errcode = '22023';
  end if;

  -- Auto-resolve student_profile_id if null for deposit
  if trans_type = 'inflow' and resolved_profile_id is null then
    if resolved_student_id is not null then
      select id, full_name into resolved_profile_id, resolved_student_name
      from classmate.profiles
      where batch_id = target_batch and student_id = resolved_student_id and verification_status = 'active'
      limit 1;
    elsif resolved_student_name is not null then
      select id, student_id into resolved_profile_id, resolved_student_id
      from classmate.profiles
      where batch_id = target_batch and lower(trim(full_name)) = lower(trim(resolved_student_name)) and verification_status = 'active'
      limit 1;
    end if;
  end if;

  -- Auto-resolve student_id & student_name from profile if profile given but fields missing
  if trans_type = 'inflow' and resolved_profile_id is not null then
    if resolved_student_id is null then
      select student_id into resolved_student_id
      from classmate.profiles where id = resolved_profile_id;
    end if;
    if resolved_student_name is null then
      select full_name into resolved_student_name
      from classmate.profiles where id = resolved_profile_id;
    end if;
  end if;

  -- Clear student fields for outflow expenses
  if trans_type = 'outflow' then
    resolved_profile_id := null;
    resolved_student_id := null;
    resolved_student_name := null;
  end if;

  insert into classmate.batch_fund_transactions(
    batch_id, type, amount, title, student_name, student_id, student_profile_id, transacted_at, created_by
  ) values (
    target_batch, trans_type, trans_amount, trim(trans_title),
    resolved_student_name,
    resolved_student_id,
    resolved_profile_id,
    coalesce(trans_date, current_date),
    auth.uid()
  ) returning id into new_id;

  -- If deposit and student profile linked, enqueue notification stamped with recipient_profile_id
  if trans_type = 'inflow' and resolved_profile_id is not null then
    select department_id into v_dept_id from classmate.batches where id = target_batch;
    insert into classmate.notification_outbox(
      kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
    ) values (
      'batch_fund_deposit', new_id, target_batch, v_dept_id,
      classmate.routing_label(target_batch), resolved_profile_id
    );
    perform classmate.request_notification_dispatch();
  end if;

  return new_id;
end $$;

-- 6. Update record_batch_fund_transactions_bulk: Stamp recipient_profile_id on notification_outbox
create or replace function classmate.record_batch_fund_transactions_bulk(
  target_batch uuid,
  entries jsonb
)
returns int
language plpgsql security definer set search_path = '' as $$
declare
  elem jsonb;
  inserted_count int := 0;
  e_type text;
  e_amount numeric;
  e_title text;
  e_student_name text;
  e_student_id text;
  e_student_profile_id uuid;
  e_date date;
  new_id uuid;
  v_dept_id uuid;
  has_deposits boolean := false;
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Only class representatives can record fund transactions' using errcode = '42501';
  end if;

  if entries is null or jsonb_typeof(entries) != 'array' or jsonb_array_length(entries) = 0 then
    return 0;
  end if;

  select department_id into v_dept_id from classmate.batches where id = target_batch;

  for elem in select * from jsonb_array_elements(entries)
  loop
    e_type := lower(coalesce(elem->>'type', 'inflow'));
    if e_type not in ('inflow', 'outflow') then e_type := 'inflow'; end if;
    
    e_amount := coalesce((elem->>'amount')::numeric, 0);
    e_title := trim(coalesce(elem->>'title', ''));
    e_student_name := nullif(trim(coalesce(elem->>'student_name', '')), '');
    e_student_id := nullif(trim(coalesce(elem->>'student_id', '')), '');
    
    begin
      e_student_profile_id := (elem->>'student_profile_id')::uuid;
    exception when others then
      e_student_profile_id := null;
    end;

    begin
      e_date := coalesce((elem->>'transacted_at')::date, current_date);
    exception when others then
      e_date := current_date;
    end;

    -- Auto-resolve student profile if missing for deposit
    if e_type = 'inflow' and e_student_profile_id is null then
      if e_student_id is not null then
        select id, full_name into e_student_profile_id, e_student_name
        from classmate.profiles
        where batch_id = target_batch and student_id = e_student_id and verification_status = 'active'
        limit 1;
      elsif e_student_name is not null then
        select id, student_id into e_student_profile_id, e_student_id
        from classmate.profiles
        where batch_id = target_batch and lower(trim(full_name)) = lower(trim(e_student_name)) and verification_status = 'active'
        limit 1;
      end if;
    end if;

    if e_amount > 0 and e_title != '' then
      insert into classmate.batch_fund_transactions(
        batch_id, type, amount, title, student_name, student_id, student_profile_id, transacted_at, created_by
      ) values (
        target_batch, e_type, e_amount, e_title, e_student_name, e_student_id, e_student_profile_id, e_date, auth.uid()
      ) returning id into new_id;
      
      inserted_count := inserted_count + 1;

      if e_type = 'inflow' and e_student_profile_id is not null then
        insert into classmate.notification_outbox(
          kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
        ) values (
          'batch_fund_deposit', new_id, target_batch, v_dept_id,
          classmate.routing_label(target_batch), e_student_profile_id
        );
        has_deposits := true;
      end if;
    end if;
  end loop;

  if has_deposits then
    perform classmate.request_notification_dispatch();
  end if;

  return inserted_count;
end $$;

-- 7. Update update_batch_fund_transaction: Stamp recipient_profile_id on notification_outbox
create or replace function classmate.update_batch_fund_transaction(
  trans_id uuid,
  trans_type text,
  trans_amount numeric,
  trans_title text,
  trans_student_name text default null,
  trans_student_id text default null,
  trans_date date default current_date,
  trans_student_profile_id uuid default null
)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  curr_batch_id uuid;
  resolved_profile_id uuid := trans_student_profile_id;
  resolved_student_id text := nullif(trim(coalesce(trans_student_id, '')), '');
  resolved_student_name text := nullif(trim(coalesce(trans_student_name, '')), '');
  v_dept_id uuid;
begin
  select batch_id into curr_batch_id
  from classmate.batch_fund_transactions
  where id = trans_id;

  if not found then
    raise exception 'Transaction not found' using errcode = '42704';
  end if;

  if not classmate.can_manage_batch_fund(curr_batch_id) then
    raise exception 'Only class representatives can update fund transactions' using errcode = '42501';
  end if;

  if trans_type not in ('inflow', 'outflow') then
    raise exception 'Transaction type must be inflow or outflow' using errcode = '22023';
  end if;

  if trans_amount <= 0 or trans_amount is null then
    raise exception 'Amount must be greater than zero' using errcode = '22023';
  end if;

  if trim(coalesce(trans_title, '')) = '' then
    raise exception 'Title is required' using errcode = '22023';
  end if;

  -- Auto-resolve student_profile_id if null for deposit
  if trans_type = 'inflow' and resolved_profile_id is null then
    if resolved_student_id is not null then
      select id, full_name into resolved_profile_id, resolved_student_name
      from classmate.profiles
      where batch_id = curr_batch_id and student_id = resolved_student_id and verification_status = 'active'
      limit 1;
    elsif resolved_student_name is not null then
      select id, student_id into resolved_profile_id, resolved_student_id
      from classmate.profiles
      where batch_id = curr_batch_id and lower(trim(full_name)) = lower(trim(resolved_student_name)) and verification_status = 'active'
      limit 1;
    end if;
  end if;

  -- Auto-resolve student_id & student_name from profile if profile given but fields missing
  if trans_type = 'inflow' and resolved_profile_id is not null then
    if resolved_student_id is null then
      select student_id into resolved_student_id
      from classmate.profiles where id = resolved_profile_id;
    end if;
    if resolved_student_name is null then
      select full_name into resolved_student_name
      from classmate.profiles where id = resolved_profile_id;
    end if;
  end if;

  -- Clear student fields for outflow expenses
  if trans_type = 'outflow' then
    resolved_profile_id := null;
    resolved_student_id := null;
    resolved_student_name := null;
  end if;

  update classmate.batch_fund_transactions set
    type = trans_type,
    amount = trans_amount,
    title = trim(trans_title),
    student_name = resolved_student_name,
    student_id = resolved_student_id,
    student_profile_id = resolved_profile_id,
    transacted_at = coalesce(trans_date, transacted_at),
    updated_at = now()
  where id = trans_id;

  -- If deposit and student profile linked, enqueue update confirmation notification
  if trans_type = 'inflow' and resolved_profile_id is not null then
    select department_id into v_dept_id from classmate.batches where id = curr_batch_id;
    insert into classmate.notification_outbox(
      kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
    ) values (
      'batch_fund_update', trans_id, curr_batch_id, v_dept_id,
      classmate.routing_label(curr_batch_id), resolved_profile_id
    );
    perform classmate.request_notification_dispatch();
  end if;
end $$;

-- 8. Update process_rfid_scan: Stamp recipient_profile_id on attendance_marked notification
create or replace function classmate.process_rfid_scan(
  p_card_uid text,
  p_device_id text default 'ESP32-READER-01'
)
returns jsonb
language plpgsql security definer set search_path = '' as $$
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

  -- Check if already marked present in this session
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

  -- Queue student confirmation notification STAMPED with recipient_profile_id
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
  ) values (
    'attendance_marked', v_record_id, stu.batch_id,
    (select department_id from classmate.profiles where id = stu.id),
    'student_' || stu.id::text, stu.id
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

-- 9. Cancel any remaining pending/leased jobs that were leaked to non-target students
update classmate.notification_jobs j
set state = 'cancelled', last_error = 'dropped: broadcast leak fix'
from classmate.notification_outbox e
where j.event_id = e.id
  and j.state in ('pending', 'leased')
  and (
    (e.kind in ('batch_fund_deposit', 'batch_fund_update') and exists (
      select 1 from classmate.batch_fund_transactions t
      where t.id = e.record_id and t.student_profile_id is distinct from j.profile_id
    ))
    or
    (e.kind = 'attendance_marked' and exists (
      select 1 from classmate.attendance_records ar
      where ar.id = e.record_id and ar.student_id is distinct from j.profile_id
    ))
    or
    (e.recipient_profile_id is not null and j.profile_id <> e.recipient_profile_id)
  );

-- Backfill recipient_profile_id on existing outbox rows for deposits and attendance
update classmate.notification_outbox e
set recipient_profile_id = t.student_profile_id
from classmate.batch_fund_transactions t
where e.record_id = t.id
  and e.kind in ('batch_fund_deposit', 'batch_fund_update')
  and e.recipient_profile_id is null;

update classmate.notification_outbox e
set recipient_profile_id = ar.student_id
from classmate.attendance_records ar
where e.record_id = ar.id
  and e.kind = 'attendance_marked'
  and e.recipient_profile_id is null;

-- 10. Grants and Permissions
revoke all on function classmate.notification_recipients(uuid) from public, anon, authenticated;
grant execute on function classmate.notification_recipients(uuid) to service_role;

revoke all on function classmate.notification_device_allowed(uuid, uuid, text) from public, anon, authenticated;
grant execute on function classmate.notification_device_allowed(uuid, uuid, text) to service_role;

revoke all on function classmate.notification_job_context(uuid, text, uuid) from public, anon, authenticated;
grant execute on function classmate.notification_job_context(uuid, text, uuid) to service_role;

revoke all on function classmate.academic_notification_job_context(uuid, text, uuid) from public, anon, authenticated;
grant execute on function classmate.academic_notification_job_context(uuid, text, uuid) to service_role;

revoke all on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

revoke all on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) from public, anon;
grant execute on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) to authenticated;

revoke all on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;

notify pgrst, 'reload schema';
commit;
