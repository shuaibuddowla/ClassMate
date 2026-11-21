-- ============================================================================
-- Migration: 202611220003_batch_fund_dues_reminders.sql
-- Description:
--   1. Creates classmate.batch_fund_reminders table to track dues reminder records.
--   2. Strict batch_id and student profile isolation with row-level security.
--   3. Provides classmate.send_batch_fund_dues_reminder RPC to target only unpaid students.
--   4. Updates classmate.notification_job_context to format batch_fund_reminder payloads.
--   5. Dispatches notifications immediately via FCM.
-- ============================================================================

begin;

-- 1. Create classmate.batch_fund_reminders table
create table if not exists classmate.batch_fund_reminders (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  recipient_profile_id uuid not null references classmate.profiles(id) on delete cascade,
  collection_title text not null,
  reminder_title text not null,
  reminder_body text not null,
  created_by uuid not null references classmate.profiles(id),
  created_at timestamptz not null default now()
);

create index if not exists idx_batch_fund_reminders_batch 
  on classmate.batch_fund_reminders (batch_id);

create index if not exists idx_batch_fund_reminders_recipient 
  on classmate.batch_fund_reminders (recipient_profile_id);

alter table classmate.batch_fund_reminders enable row level security;
grant select on classmate.batch_fund_reminders to authenticated, service_role;

drop policy if exists batch_fund_reminders_read on classmate.batch_fund_reminders;
create policy batch_fund_reminders_read on classmate.batch_fund_reminders
  for select to authenticated
  using (recipient_profile_id = auth.uid() or classmate.can_read_batch(batch_id));

-- 2. Update notification_job_context to support batch_fund_reminder
create or replace function classmate.notification_job_context(
  target_event uuid,
  target_hash text,
  target_lease uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  j classmate.notification_jobs%rowtype;
  r classmate.blood_requests%rowtype;
  t classmate.batch_fund_transactions%rowtype;
  e classmate.notification_outbox%rowtype;
  rem classmate.batch_fund_reminders%rowtype;
  matched boolean;
  own_group text;
  amt_str text;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if not found then return null; end if;

  -- A. Batch Fund Dues Reminder
  if e.kind = 'batch_fund_reminder' then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select * into rem from classmate.batch_fund_reminders where id = e.record_id;
    if not found then return null; end if;

    -- Strict security guard: Never generate dues reminder for non-target user
    if rem.recipient_profile_id is distinct from j.profile_id then
      return null;
    end if;
    if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
      return null;
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', e.batch_id,
      'recipient_id', j.profile_id,
      'kind', e.kind,
      'record_id', rem.id::text,
      'title', rem.reminder_title,
      'body', rem.reminder_body,
      'collection_title', rem.collection_title,
      'failures', j.failures
    );
  end if;

  -- B. Batch Fund Deposit / Update
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

  -- C. Blood Request
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

-- 3. Create RPC send_batch_fund_dues_reminder
create or replace function classmate.send_batch_fund_dues_reminder(
  target_batch uuid,
  target_collection text,
  unpaid_profile_ids uuid[]
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_dept_id uuid;
  v_pid uuid;
  v_rem_id uuid;
  v_title text;
  v_body text;
  v_count integer := 0;
  v_col_display text;
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Access denied: CR or Admin privileges required.' using errcode = '42501';
  end if;

  if unpaid_profile_ids is null or array_length(unpaid_profile_ids, 1) is null or array_length(unpaid_profile_ids, 1) = 0 then
    return 0;
  end if;

  select department_id into v_dept_id from classmate.batches where id = target_batch;
  if v_dept_id is null then
    raise exception 'Batch not found.' using errcode = 'P0002';
  end if;

  v_col_display := trim(coalesce(target_collection, 'Batch Fund'));
  if v_col_display = '' or lower(v_col_display) = 'all' or lower(v_col_display) = 'all collections' then
    v_col_display := 'Batch Fund Dues';
  end if;

  v_title := 'Batch Fund Reminder: ' || left(v_col_display, 50);
  v_body := 'You have pending dues for ' || v_col_display || '. Please deposit to your CR.';

  foreach v_pid in array unpaid_profile_ids loop
    -- Verify the student belongs to this batch and is active
    if exists (select 1 from classmate.profiles where id = v_pid and batch_id = target_batch and verification_status = 'active') then
      -- Insert a reminder record
      insert into classmate.batch_fund_reminders (
        batch_id,
        recipient_profile_id,
        collection_title,
        reminder_title,
        reminder_body,
        created_by
      ) values (
        target_batch,
        v_pid,
        v_col_display,
        v_title,
        v_body,
        auth.uid()
      ) returning id into v_rem_id;

      -- Enqueue in outbox for immediate dispatch
      insert into classmate.notification_outbox (
        kind,
        record_id,
        batch_id,
        department_id,
        routing_label,
        recipient_profile_id
      ) values (
        'batch_fund_reminder',
        v_rem_id,
        target_batch,
        v_dept_id,
        classmate.routing_label(target_batch),
        v_pid
      ) on conflict (kind, record_id) do nothing;

      v_count := v_count + 1;
    end if;
  end loop;

  if v_count > 0 then
    perform classmate.request_notification_dispatch();
  end if;

  return v_count;
end;
$$;

revoke all on function classmate.send_batch_fund_dues_reminder(uuid, text, uuid[]) from public, anon;
grant execute on function classmate.send_batch_fund_dues_reminder(uuid, text, uuid[]) to authenticated;

commit;
