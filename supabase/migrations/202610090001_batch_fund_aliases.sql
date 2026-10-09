begin;

-- 1. Drop existing single-uuid delete_batch_fund_transaction and recreate accepting both trans_id and target_transaction
drop function if exists classmate.delete_batch_fund_transaction(uuid);
create or replace function classmate.delete_batch_fund_transaction(
  trans_id uuid default null,
  target_transaction uuid default null
)
returns void
language plpgsql security definer set search_path='' as $$
declare
  actual_id uuid := coalesce(trans_id, target_transaction);
  target_batch uuid;
begin
  if actual_id is null then
    raise exception 'Transaction ID is required' using errcode='22023';
  end if;

  select batch_id into target_batch
  from classmate.batch_fund_transactions where id = actual_id;

  if target_batch is null then return; end if;

  if not classmate.can_manage_batch_fund(target_batch) then
    raise exception 'Permission denied' using errcode='42501';
  end if;

  delete from classmate.batch_fund_transactions where id = actual_id;
end $$;

revoke all on function classmate.delete_batch_fund_transaction(uuid, uuid) from public, anon;
grant execute on function classmate.delete_batch_fund_transaction(uuid, uuid) to authenticated;

-- 2. Alias delete_batch_fund accepting both trans_id and target_transaction
create or replace function classmate.delete_batch_fund(
  trans_id uuid default null,
  target_transaction uuid default null
)
returns void
language plpgsql security definer set search_path='' as $$
begin
  perform classmate.delete_batch_fund_transaction(trans_id => trans_id, target_transaction => target_transaction);
end $$;

revoke all on function classmate.delete_batch_fund(uuid, uuid) from public, anon;
grant execute on function classmate.delete_batch_fund(uuid, uuid) to authenticated;

-- 3. Overload update_batch_fund_transaction for target_transaction, new_amount, new_title, new_student_name
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
begin
  select type into curr_type from classmate.batch_fund_transactions where id = target_transaction;
  if curr_type is null then
    raise exception 'Transaction not found' using errcode='P0002';
  end if;

  perform classmate.update_batch_fund_transaction(
    trans_id => target_transaction,
    trans_type => curr_type,
    trans_amount => new_amount,
    trans_title => new_title,
    trans_student_name => new_student_name
  );
end $$;

revoke all on function classmate.update_batch_fund_transaction(uuid, numeric, text, text) from public, anon;
grant execute on function classmate.update_batch_fund_transaction(uuid, numeric, text, text) to authenticated;

notify pgrst, 'reload schema';

commit;
