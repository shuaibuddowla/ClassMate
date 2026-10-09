begin;

-- 1. Add student_profile_id column to batch_fund_transactions
alter table classmate.batch_fund_transactions
  add column if not exists student_profile_id uuid references classmate.profiles(id) on delete set null;

create index if not exists batch_fund_transactions_profile_idx 
  on classmate.batch_fund_transactions(batch_id, student_profile_id);

-- 2. Update start_ai_request to allow 'batch_fund_parse'
create or replace function classmate.start_ai_request(target_id uuid,target_batch uuid,target_mode text,target_hash text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare r classmate.ai_requests; attempts integer;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Batch access denied' using errcode='42501'; end if;
  if target_mode not in ('agent','compose','translate','batch_fund_parse') then raise exception 'Unknown AI mode'; end if;
  if target_mode in ('compose','batch_fund_parse') and not classmate.ai_can_write(target_batch) then raise exception 'Admin or batch CR required' using errcode='42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,0));
  select * into r from classmate.ai_requests where id=target_id for update;
  if found then
    if r.user_id<>auth.uid() or r.batch_id<>target_batch or r.mode<>target_mode or r.input_hash<>target_hash then raise exception 'AI request conflict' using errcode='42501'; end if;
    if coalesce((r.plan->>'owner_only')::boolean,false) and not classmate.is_owner() then
      raise exception 'Owner-only AI history access denied' using errcode='42501'; end if;
    if r.status in ('ready','complete') then return to_jsonb(r); end if;
    if r.status='generating' and r.updated_at>now()-interval '90 seconds' then raise exception 'This AI request is still running'; end if;
  end if;
  -- Failed retries are attempts too; replaying a ready/complete result costs nothing.
  insert into classmate.ai_rate_limits(user_id) values(auth.uid())
  on conflict(user_id) do update set
   calls=case when classmate.ai_rate_limits.window_start<=now()-interval '1 minute' then 1 else classmate.ai_rate_limits.calls+1 end,
   window_start=case when classmate.ai_rate_limits.window_start<=now()-interval '1 minute' then now() else classmate.ai_rate_limits.window_start end
  returning calls into attempts;
  if attempts>10 then raise exception 'Please wait a minute before another AI request'; end if;
  if r.id is not null then
    update classmate.ai_requests set status='generating',lease=gen_random_uuid(),updated_at=now() where id=target_id returning * into r;
  else
    insert into classmate.ai_requests(id,user_id,batch_id,mode,input_hash) values(target_id,auth.uid(),target_batch,target_mode,target_hash) returning * into r;
  end if;
  return to_jsonb(r);
end $$;

-- 3. Update batch_fund_payers to prioritize student_profile_id
create or replace function classmate.batch_fund_payers(target_batch uuid)
returns table(
  profile_id uuid,
  full_name text,
  student_id text,
  total_paid numeric,
  payment_count bigint,
  last_paid_at date
)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch fund access denied' using errcode='42501';
  end if;

  return query
  select 
    p.id as profile_id,
    p.full_name,
    p.student_id,
    coalesce(sum(case when t.type = 'inflow' then t.amount else 0 end), 0) as total_paid,
    count(t.id) filter (where t.type = 'inflow') as payment_count,
    max(t.transacted_at) filter (where t.type = 'inflow') as last_paid_at
  from classmate.profiles p
  left join classmate.batch_fund_transactions t 
    on t.batch_id = target_batch 
   and (
     (t.student_profile_id is not null and t.student_profile_id = p.id)
     or (t.student_profile_id is null and t.student_id is not null and t.student_id = p.student_id)
     or (t.student_profile_id is null and t.student_name is not null and lower(trim(t.student_name)) = lower(trim(p.full_name)))
   )
  where p.verification_status = 'active'
    and p.role = 'student'
    and p.batch_id = target_batch
  group by p.id, p.full_name, p.student_id
  order by total_paid desc, p.student_id asc nulls last, p.full_name asc;
end $$;

revoke all on function classmate.batch_fund_payers(uuid) from public, anon;
grant execute on function classmate.batch_fund_payers(uuid) to authenticated;

-- 4. Update batch_fund_transactions to return student_profile_id
drop function if exists classmate.batch_fund_transactions(uuid, text, text, int, int);
create or replace function classmate.batch_fund_transactions(
  target_batch uuid,
  filter_type text default 'all',
  query_text text default '',
  result_offset int default 0,
  page_limit int default 50
)
returns table(
  id uuid,
  type text,
  amount numeric,
  title text,
  student_name text,
  student_id text,
  student_profile_id uuid,
  transacted_at date,
  created_at timestamptz,
  created_by_name text,
  can_edit boolean
)
language plpgsql stable security definer set search_path='' as $$
declare
  user_is_cr boolean := false;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch fund access denied' using errcode='42501';
  end if;

  user_is_cr := classmate.can_manage_batch_fund(target_batch);

  return query
  select 
    t.id,
    t.type,
    t.amount,
    t.title,
    t.student_name,
    t.student_id,
    t.student_profile_id,
    t.transacted_at,
    t.created_at,
    p.full_name as created_by_name,
    user_is_cr as can_edit
  from classmate.batch_fund_transactions t
  left join classmate.profiles p on p.id = t.created_by
  where t.batch_id = target_batch
    and (filter_type = 'all' or t.type = filter_type)
    and (query_text = '' or position(lower(trim(query_text)) in lower(t.title || ' ' || coalesce(t.student_name, '') || ' ' || coalesce(t.student_id, ''))) > 0)
  order by t.transacted_at desc, t.created_at desc
  limit page_limit offset result_offset;
end $$;

revoke all on function classmate.batch_fund_transactions(uuid, text, text, int, int) from public, anon;
grant execute on function classmate.batch_fund_transactions(uuid, text, text, int, int) to authenticated;

-- 5. Update record_batch_fund_transaction to accept student_profile_id
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

  insert into classmate.batch_fund_transactions(
    batch_id, type, amount, title, student_name, student_id, student_profile_id, transacted_at, created_by
  ) values (
    target_batch, trans_type, trans_amount, trim(trans_title),
    nullif(trim(coalesce(trans_student_name, '')), ''),
    nullif(trim(coalesce(trans_student_id, '')), ''),
    trans_student_profile_id,
    coalesce(trans_date, current_date),
    auth.uid()
  ) returning id into new_id;

  return new_id;
end $$;

revoke all on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

-- 6. Update record_batch_fund_transactions_bulk to accept student_profile_id
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
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Only class representatives can record fund transactions' using errcode='42501';
  end if;

  if entries is null or jsonb_typeof(entries) != 'array' or jsonb_array_length(entries) = 0 then
    return 0;
  end if;

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

    if e_amount > 0 and e_title != '' then
      insert into classmate.batch_fund_transactions(
        batch_id, type, amount, title, student_name, student_id, student_profile_id, transacted_at, created_by
      ) values (
        target_batch, e_type, e_amount, e_title, e_student_name, e_student_id, e_student_profile_id, e_date, auth.uid()
      );
      inserted_count := inserted_count + 1;
    end if;
  end loop;

  return inserted_count;
end $$;

revoke all on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) from public, anon;
grant execute on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) to authenticated;

-- 7. Update update_batch_fund_transaction to accept student_profile_id
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
  target_batch uuid;
begin
  select batch_id into target_batch
  from classmate.batch_fund_transactions where id = trans_id;

  if target_batch is null then
    raise exception 'Transaction not found' using errcode='P0002';
  end if;

  if not classmate.can_manage_batch_fund(target_batch) then
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

  update classmate.batch_fund_transactions
  set 
    type = trans_type,
    amount = trans_amount,
    title = trim(trans_title),
    student_name = nullif(trim(coalesce(trans_student_name, '')), ''),
    student_id = nullif(trim(coalesce(trans_student_id, '')), ''),
    student_profile_id = trans_student_profile_id,
    transacted_at = coalesce(trans_date, current_date),
    updated_at = now()
  where id = trans_id;
end $$;

revoke all on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date, uuid) to authenticated;

commit;
