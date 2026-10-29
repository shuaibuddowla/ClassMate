begin;

-- ============================================================================
-- 1. Update batch_friend_details: Include is_cr and is_online in profile view
-- ============================================================================
create or replace function classmate.batch_friend_details(target_batch uuid, target_profile uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  result jsonb;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not classmate.batch_directory_member(target_batch, target_profile) then
    raise exception 'This profile is unavailable in your batch' using errcode='42501';
  end if;

  select jsonb_build_object(
    'profile_id', p.id,
    'full_name', p.full_name,
    'student_id', p.student_id,
    'role', p.role,
    'is_cr', p.is_cr,
    'is_online', exists(select 1 from classmate.active_presence ap where ap.profile_id = p.id and ap.last_seen_at > now() - interval '60 seconds'),
    'mobile_number', p.mobile_number,
    'home_town', p.home_town,
    'blood_group', p.blood_group,
    'current_residence', p.current_residence,
    'avatar_url', case when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') end
  )
  into result
  from classmate.profiles p
  join auth.users u on u.id = p.id
  where p.id = target_profile;

  return result;
end $$;

revoke all on function classmate.batch_friend_details(uuid, uuid) from public, anon;
grant execute on function classmate.batch_friend_details(uuid, uuid) to authenticated;

-- ============================================================================
-- 2. Update record_batch_fund_transaction: Auto-resolve student_id if profile given
-- ============================================================================
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
  resolved_student_id text := nullif(trim(coalesce(trans_student_id, '')), '');
  resolved_student_name text := nullif(trim(coalesce(trans_student_name, '')), '');
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

  -- If deposit and student profile linked, enqueue notification
  if trans_type = 'inflow' and resolved_profile_id is not null then
    select department_id into v_dept_id from classmate.batches where id = target_batch;
    insert into classmate.notification_outbox(kind, record_id, batch_id, department_id, routing_label)
    values ('batch_fund_deposit', new_id, target_batch, v_dept_id, classmate.routing_label(target_batch));
    perform classmate.request_notification_dispatch();
  end if;

  return new_id;
end $$;

revoke all on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

-- ============================================================================
-- 3. Update update_batch_fund_transaction: Auto-resolve student_id and clean outflows
-- ============================================================================
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
  resolved_student_id text := nullif(trim(coalesce(trans_student_id, '')), '');
  resolved_student_name text := nullif(trim(coalesce(trans_student_name, '')), '');
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
    insert into classmate.notification_outbox(kind, record_id, batch_id, department_id, routing_label)
    values ('batch_fund_update', trans_id, curr_batch_id, v_dept_id, classmate.routing_label(curr_batch_id));
    perform classmate.request_notification_dispatch();
  end if;
end $$;

revoke all on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

notify pgrst, 'reload schema';

commit;
