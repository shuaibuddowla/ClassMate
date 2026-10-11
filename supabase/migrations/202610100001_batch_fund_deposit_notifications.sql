begin;

-- ============================================================================
-- 1. Notification Recipients: Support single-recipient deposit notifications
-- ============================================================================
create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language sql stable security definer set search_path='' as $$
  select * from classmate.academic_notification_recipients(target_event)
  where exists (
    select 1 from classmate.notification_outbox e
    where e.id = target_event and e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update')
  )
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

-- ============================================================================
-- 2. Notification Device Allowed Check: Verify student recipient identity
-- ============================================================================
create or replace function classmate.notification_device_allowed(target_event uuid, target_profile uuid, target_hash text)
returns boolean
language sql stable security definer set search_path='' as $$
  select (
    exists (
      select 1 from classmate.notification_outbox e
      where e.id = target_event and e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update')
    ) and classmate.academic_notification_device_allowed(target_event, target_profile, target_hash)
  ) or exists(
    select 1 from classmate.notification_outbox e
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event and e.kind = 'blood_request' and classmate.blood_broadcast_allowed(e.record_id, target_profile)
  ) or exists(
    select 1 from classmate.notification_outbox e
    join classmate.batch_fund_transactions t on t.id = e.record_id
    join classmate.device_tokens d on d.token_hash = target_hash and d.profile_id = target_profile
    where e.id = target_event
      and e.kind in ('batch_fund_deposit', 'batch_fund_update')
      and t.student_profile_id = target_profile
  );
$$;

-- ============================================================================
-- 3. Notification Job Context: Build customized payload for deposits
-- ============================================================================
create or replace function classmate.notification_job_context(target_event uuid, target_hash text, target_lease uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
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
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then return null; end if;

    select * into t from classmate.batch_fund_transactions where id = e.record_id;
    if not found then return null; end if;

    if t.amount = trunc(t.amount) then
      amt_str := trim(to_char(t.amount, 'FM999,999,990'));
    else
      amt_str := trim(to_char(t.amount, 'FM999,999,990.00'));
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', e.batch_id,
      'title', case when e.kind = 'batch_fund_update' then 'Deposit Updated: ৳' || amt_str else 'Deposit Confirmed: ৳' || amt_str end,
      'body', case when e.kind = 'batch_fund_update'
        then 'Your deposit of ৳' || amt_str || ' for "' || left(t.title, 40) || '" has been updated.'
        else 'Your deposit of ৳' || amt_str || ' for "' || left(t.title, 40) || '" has been recorded.' end,
      'failures', j.failures
    );
  end if;

  if e.kind = 'blood_request' then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then return null; end if;

    select r0.* into r from classmate.blood_requests r0 where r0.id = e.record_id;
    if not found then return null; end if;

    matched := classmate.blood_matching_alert(r.id, j.profile_id);
    select blood_group into own_group from classmate.profiles where id = j.profile_id;
    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
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

-- ============================================================================
-- 4. Record Single Transaction: Auto-resolve student profile and enqueue outbox
-- ============================================================================
drop function if exists classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date);
drop function if exists classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid);

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
language plpgsql security definer set search_path='' as $$
declare
  new_id uuid;
  resolved_profile_id uuid := trans_student_profile_id;
  v_dept_id uuid;
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Only class representatives can record fund transactions' using errcode='42501';
  end if;

  if trans_type not in ('inflow', 'outflow') then
    raise exception 'Transaction type must be inflow or outflow' using errcode='22023';
  end if;

  if trans_amount <= 0 or trans_amount is null then
    raise exception 'Amount must be greater than zero' using errcode='22023';
  end if;

  if trim(coalesce(trans_title, '')) = '' then
    raise exception 'Title is required' using errcode='22023';
  end if;

  -- Auto-resolve student_profile_id if null for deposit
  if trans_type = 'inflow' and resolved_profile_id is null then
    if trans_student_id is not null and trim(trans_student_id) <> '' then
      select id into resolved_profile_id from classmate.profiles
      where batch_id = target_batch and student_id = trim(trans_student_id) and verification_status = 'active'
      limit 1;
    elsif trans_student_name is not null and trim(trans_student_name) <> '' then
      select id into resolved_profile_id from classmate.profiles
      where batch_id = target_batch and lower(trim(full_name)) = lower(trim(trans_student_name)) and verification_status = 'active'
      limit 1;
    end if;
  end if;

  insert into classmate.batch_fund_transactions(
    batch_id, type, amount, title, student_name, student_id, student_profile_id, transacted_at, created_by
  ) values (
    target_batch, trans_type, trans_amount, trim(trans_title),
    nullif(trim(coalesce(trans_student_name, '')), ''),
    nullif(trim(coalesce(trans_student_id, '')), ''),
    resolved_profile_id,
    coalesce(trans_date, current_date),
    auth.uid()
  ) returning id into new_id;

  -- If deposit and student profile linked, enqueue notification
  if trans_type = 'inflow' and resolved_profile_id is not null then
    select department_id into v_dept_id from classmate.batches where id = target_batch;
    insert into classmate.notification_outbox(kind, record_id, batch_id, department_id, routing_label)
    values ('batch_fund_deposit', new_id, target_batch, v_dept_id, classmate.routing_label(target_batch));
    perform classmate.request_notification_dispatch();
  end if;

  return new_id;
end $$;

-- ============================================================================
-- 5. Record Bulk Transactions: Auto-resolve student profiles and enqueue outbox
-- ============================================================================
create or replace function classmate.record_batch_fund_transactions_bulk(
  target_batch uuid,
  entries jsonb
)
returns int
language plpgsql security definer set search_path='' as $$
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
    raise exception 'Only class representatives can record fund transactions' using errcode='42501';
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
        select id into e_student_profile_id from classmate.profiles
        where batch_id = target_batch and student_id = e_student_id and verification_status = 'active'
        limit 1;
      elsif e_student_name is not null then
        select id into e_student_profile_id from classmate.profiles
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
        insert into classmate.notification_outbox(kind, record_id, batch_id, department_id, routing_label)
        values ('batch_fund_deposit', new_id, target_batch, v_dept_id, classmate.routing_label(target_batch));
        has_deposits := true;
      end if;
    end if;
  end loop;

  if has_deposits then
    perform classmate.request_notification_dispatch();
  end if;

  return inserted_count;
end $$;

-- ============================================================================
-- 6. Update Transaction: Enqueue update confirmation notification
-- ============================================================================
drop function if exists classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date);
drop function if exists classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid);

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
language plpgsql security definer set search_path='' as $$
declare
  curr_batch_id uuid;
  resolved_profile_id uuid := trans_student_profile_id;
  v_dept_id uuid;
begin
  select batch_id into curr_batch_id
  from classmate.batch_fund_transactions
  where id = trans_id;

  if not found then
    raise exception 'Transaction not found' using errcode='42704';
  end if;

  if not classmate.can_manage_batch_fund(curr_batch_id) then
    raise exception 'Only class representatives can update fund transactions' using errcode='42501';
  end if;

  if trans_type not in ('inflow', 'outflow') then
    raise exception 'Transaction type must be inflow or outflow' using errcode='22023';
  end if;

  if trans_amount <= 0 or trans_amount is null then
    raise exception 'Amount must be greater than zero' using errcode='22023';
  end if;

  if trim(coalesce(trans_title, '')) = '' then
    raise exception 'Title is required' using errcode='22023';
  end if;

  -- Auto-resolve student_profile_id if null for deposit
  if trans_type = 'inflow' and resolved_profile_id is null then
    if trans_student_id is not null and trim(trans_student_id) <> '' then
      select id into resolved_profile_id from classmate.profiles
      where batch_id = curr_batch_id and student_id = trim(trans_student_id) and verification_status = 'active'
      limit 1;
    elsif trans_student_name is not null and trim(trans_student_name) <> '' then
      select id into resolved_profile_id from classmate.profiles
      where batch_id = curr_batch_id and lower(trim(full_name)) = lower(trim(trans_student_name)) and verification_status = 'active'
      limit 1;
    end if;
  end if;

  update classmate.batch_fund_transactions set
    type = trans_type,
    amount = trans_amount,
    title = trim(trans_title),
    student_name = nullif(trim(coalesce(trans_student_name, '')), ''),
    student_id = nullif(trim(coalesce(trans_student_id, '')), ''),
    student_profile_id = resolved_profile_id,
    transacted_at = coalesce(trans_date, transacted_at),
    updated_at = now()
  where id = trans_id;

  -- If deposit and student profile linked, enqueue update confirmation notification
  if trans_type = 'inflow' and resolved_profile_id is not null then
    select department_id into v_dept_id from classmate.batches where id = curr_batch_id;
    insert into classmate.notification_outbox(kind, record_id, batch_id, department_id, routing_label)
    values ('batch_fund_update', trans_id, curr_batch_id, v_dept_id, classmate.routing_label(curr_batch_id));
    perform classmate.request_notification_dispatch();
  end if;
end $$;

-- Maintain the simple 4-argument overload for backwards compatibility
create or replace function classmate.update_batch_fund_transaction(
  target_transaction uuid,
  new_amount numeric,
  new_title text,
  new_student_name text default null
)
returns void
language plpgsql security definer set search_path='' as $$
declare
  curr_type text;
  curr_student_id text;
  curr_student_profile_id uuid;
  curr_date date;
begin
  select type, student_id, student_profile_id, transacted_at
  into curr_type, curr_student_id, curr_student_profile_id, curr_date
  from classmate.batch_fund_transactions
  where id = target_transaction;

  if not found then
    raise exception 'Transaction not found' using errcode='42704';
  end if;

  perform classmate.update_batch_fund_transaction(
    trans_id => target_transaction,
    trans_type => curr_type,
    trans_amount => new_amount,
    trans_title => new_title,
    trans_student_name => new_student_name,
    trans_student_id => curr_student_id,
    trans_date => curr_date,
    trans_student_profile_id => curr_student_profile_id
  );
end $$;

-- ============================================================================
-- 7. Permissions and Schema Reload
-- ============================================================================
revoke all on function classmate.notification_recipients(uuid) from public, anon, authenticated;
grant execute on function classmate.notification_recipients(uuid) to service_role;

revoke all on function classmate.notification_device_allowed(uuid, uuid, text) from public, anon, authenticated;
grant execute on function classmate.notification_device_allowed(uuid, uuid, text) to service_role;

revoke all on function classmate.notification_job_context(uuid, text, uuid) from public, anon, authenticated;
grant execute on function classmate.notification_job_context(uuid, text, uuid) to service_role;

revoke all on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

revoke all on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) from public, anon;
grant execute on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) to authenticated;

revoke all on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

revoke all on function classmate.update_batch_fund_transaction(uuid, numeric, text, text) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, numeric, text, text) to authenticated;

notify pgrst, 'reload schema';

commit;
