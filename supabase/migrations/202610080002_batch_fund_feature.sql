begin;

-- 1. Batch Fund Settings (custom description & metadata)
create table if not exists classmate.batch_fund_settings (
  batch_id uuid primary key references classmate.batches(id) on delete cascade,
  fund_description text not null default 'Used for mess, events, jersey, and other batch expenses.',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id)
);

alter table classmate.batch_fund_settings enable row level security;
revoke all on classmate.batch_fund_settings from public, anon;
grant select, insert, update on classmate.batch_fund_settings to authenticated;

-- 2. Batch Fund Transactions Ledger
create table if not exists classmate.batch_fund_transactions (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  type text not null check (type in ('inflow', 'outflow')),
  amount numeric(12, 2) not null check (amount > 0),
  title text not null,
  student_name text,
  student_id text,
  transacted_at date not null default current_date,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists batch_fund_transactions_batch_date_idx 
  on classmate.batch_fund_transactions(batch_id, transacted_at desc, created_at desc);

create index if not exists batch_fund_transactions_student_idx 
  on classmate.batch_fund_transactions(batch_id, student_id);

alter table classmate.batch_fund_transactions enable row level security;
revoke all on classmate.batch_fund_transactions from public, anon;
grant select, insert, update, delete on classmate.batch_fund_transactions to authenticated;

-- Helper: Check if user can manage batch fund (CR or App Owner)
create or replace function classmate.can_manage_batch_fund(target_batch uuid)
returns boolean language sql stable security definer set search_path='' as $$
  select exists(
    select 1 from classmate.profiles p
    where p.id = auth.uid()
      and p.verification_status = 'active'
      and (
        (p.role = 'student' and p.is_cr and p.batch_id = target_batch and p.cr_batch_id = target_batch)
        or (p.role = 'admin' and exists(select 1 from classmate.app_owners o where o.active and o.email = p.email))
      )
  );
$$;

revoke all on function classmate.can_manage_batch_fund(uuid) from public, anon;
grant execute on function classmate.can_manage_batch_fund(uuid) to authenticated;

-- RLS: Read transactions if user can read the batch
create policy batch_fund_transactions_read on classmate.batch_fund_transactions
  for select using (classmate.is_active() and classmate.can_read_batch(batch_id));

-- RLS: Modify transactions if CR or Owner
create policy batch_fund_transactions_write on classmate.batch_fund_transactions
  for all using (classmate.can_manage_batch_fund(batch_id))
  with check (classmate.can_manage_batch_fund(batch_id));

-- RLS: Settings
create policy batch_fund_settings_read on classmate.batch_fund_settings
  for select using (classmate.is_active() and classmate.can_read_batch(batch_id));

create policy batch_fund_settings_write on classmate.batch_fund_settings
  for all using (classmate.can_manage_batch_fund(batch_id))
  with check (classmate.can_manage_batch_fund(batch_id));

-- RPC 1: Fund Summary (Balance, Total In, Total Out, Description, Permissions)
create or replace function classmate.batch_fund_summary(target_batch uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  collected numeric(12, 2) := 0;
  spent numeric(12, 2) := 0;
  balance numeric(12, 2) := 0;
  tx_count int := 0;
  desc_text text := 'Used for mess, events, jersey, and other batch expenses.';
  user_is_cr boolean := false;
  dept_code text := '';
  batch_num int := 0;
  uni_code text := 'MBSTU';
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch fund access denied' using errcode='42501';
  end if;

  select 
    coalesce(sum(case when type = 'inflow' then amount else 0 end), 0),
    coalesce(sum(case when type = 'outflow' then amount else 0 end), 0),
    count(*)
  into collected, spent, tx_count
  from classmate.batch_fund_transactions
  where batch_id = target_batch;

  balance := collected - spent;

  select coalesce(fund_description, desc_text) into desc_text
  from classmate.batch_fund_settings where batch_id = target_batch;
  if desc_text is null or trim(desc_text) = '' then
    desc_text := 'Used for mess, events, jersey, and other batch expenses.';
  end if;

  user_is_cr := classmate.can_manage_batch_fund(target_batch);

  select d.code, b.batch_number 
  into dept_code, batch_num
  from classmate.batches b
  join classmate.departments d on d.id = b.department_id
  where b.id = target_batch;

  return jsonb_build_object(
    'current_balance', balance,
    'total_collected', collected,
    'total_spent', spent,
    'transaction_count', tx_count,
    'fund_description', desc_text,
    'is_cr', user_is_cr,
    'batch_name', coalesce(dept_code || ' ' || batch_num, 'Batch Fund'),
    'university', uni_code
  );
end $$;

revoke all on function classmate.batch_fund_summary(uuid) from public, anon;
grant execute on function classmate.batch_fund_summary(uuid) to authenticated;

-- RPC 2: Transactions list (with filter 'all' | 'inflow' | 'outflow' and search)
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

-- RPC 3: Payers tracking (List all batchmates with their aggregate paid amount)
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
     (t.student_id is not null and t.student_id = p.student_id)
     or (t.student_name is not null and lower(trim(t.student_name)) = lower(trim(p.full_name)))
   )
  where p.verification_status = 'active'
    and p.role = 'student'
    and p.batch_id = target_batch
  group by p.id, p.full_name, p.student_id
  order by total_paid desc, p.student_id asc nulls last, p.full_name asc;
end $$;

revoke all on function classmate.batch_fund_payers(uuid) from public, anon;
grant execute on function classmate.batch_fund_payers(uuid) to authenticated;

-- RPC 4: Record Single Transaction
create or replace function classmate.record_batch_fund_transaction(
  target_batch uuid,
  trans_type text,
  trans_amount numeric,
  trans_title text,
  trans_student_name text default null,
  trans_student_id text default null,
  trans_date date default current_date
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
    batch_id, type, amount, title, student_name, student_id, transacted_at, created_by
  ) values (
    target_batch, trans_type, trans_amount, trim(trans_title),
    nullif(trim(coalesce(trans_student_name, '')), ''),
    nullif(trim(coalesce(trans_student_id, '')), ''),
    coalesce(trans_date, current_date),
    auth.uid()
  ) returning id into new_id;

  return new_id;
end $$;

revoke all on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date) from public, anon;
grant execute on function classmate.record_batch_fund_transaction(uuid, text, numeric, text, text, text, date) to authenticated;

-- RPC 5: Bulk Record Transactions (For ClassMate AI bulk parse)
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
      e_date := coalesce((elem->>'transacted_at')::date, current_date);
    exception when others then
      e_date := current_date;
    end;

    if e_amount > 0 and e_title != '' then
      insert into classmate.batch_fund_transactions(
        batch_id, type, amount, title, student_name, student_id, transacted_at, created_by
      ) values (
        target_batch, e_type, e_amount, e_title, e_student_name, e_student_id, e_date, auth.uid()
      );
      inserted_count := inserted_count + 1;
    end if;
  end loop;

  return inserted_count;
end $$;

revoke all on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) from public, anon;
grant execute on function classmate.record_batch_fund_transactions_bulk(uuid, jsonb) to authenticated;

-- RPC 6: Update Transaction
create or replace function classmate.update_batch_fund_transaction(
  trans_id uuid,
  trans_type text,
  trans_amount numeric,
  trans_title text,
  trans_student_name text default null,
  trans_student_id text default null,
  trans_date date default current_date
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
    raise exception 'Permission denied' using errcode='42501';
  end if;

  if trans_type not in ('inflow', 'outflow') or trans_amount <= 0 or trim(coalesce(trans_title, '')) = '' then
    raise exception 'Invalid transaction data' using errcode='22023';
  end if;

  update classmate.batch_fund_transactions set
    type = trans_type,
    amount = trans_amount,
    title = trim(trans_title),
    student_name = nullif(trim(coalesce(trans_student_name, '')), ''),
    student_id = nullif(trim(coalesce(trans_student_id, '')), ''),
    transacted_at = coalesce(trans_date, current_date),
    updated_at = now()
  where id = trans_id;
end $$;

revoke all on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, text, numeric, text, text, text, date) to authenticated;

-- RPC 7: Delete Transaction
create or replace function classmate.delete_batch_fund_transaction(trans_id uuid)
returns void
language plpgsql security definer set search_path='' as $$
declare
  target_batch uuid;
begin
  select batch_id into target_batch
  from classmate.batch_fund_transactions where id = trans_id;

  if target_batch is null then return; end if;

  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Permission denied' using errcode='42501';
  end if;

  delete from classmate.batch_fund_transactions where id = trans_id;
end $$;

revoke all on function classmate.delete_batch_fund_transaction(uuid) from public, anon;
grant execute on function classmate.delete_batch_fund_transaction(uuid) to authenticated;

-- RPC 8: Update Description
create or replace function classmate.update_batch_fund_description(
  target_batch uuid,
  new_description text
)
returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Permission denied' using errcode='42501';
  end if;

  insert into classmate.batch_fund_settings(batch_id, fund_description, updated_at, updated_by)
  values (target_batch, trim(new_description), now(), auth.uid())
  on conflict (batch_id) do update set
    fund_description = trim(new_description),
    updated_at = now(),
    updated_by = auth.uid();
end $$;

revoke all on function classmate.update_batch_fund_description(uuid, text) from public, anon;
grant execute on function classmate.update_batch_fund_description(uuid, text) to authenticated;

notify pgrst, 'reload schema';

commit;
