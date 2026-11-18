-- Migration: Include admin batch members in batch_fund_payers
drop function if exists classmate.batch_fund_payers(uuid, text);

create or replace function classmate.batch_fund_payers(
  target_batch uuid,
  target_collection text default null
)
returns table(
  profile_id uuid,
  full_name text,
  student_id text,
  total_paid numeric,
  payment_count bigint,
  last_paid_at date
)
language plpgsql stable security definer set search_path='' as $$
declare
  clean_col text := null;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch fund access denied' using errcode='42501';
  end if;

  if target_collection is not null and trim(target_collection) <> '' and lower(trim(target_collection)) <> 'all' then
    clean_col := lower(trim(target_collection));
  end if;

  return query
  select 
    p.id as profile_id,
    p.full_name,
    p.student_id,
    coalesce(sum(case when t.type = 'inflow' then t.amount else 0 end), 0)::numeric as total_paid,
    count(t.id) filter (where t.type = 'inflow')::bigint as payment_count,
    max(t.transacted_at) filter (where t.type = 'inflow') as last_paid_at
  from classmate.profiles p
  left join classmate.batch_fund_transactions t 
    on t.batch_id = target_batch 
   and t.type = 'inflow'
   and (
     clean_col is null 
     or lower(classmate.canonical_fund_title(t.title)) = clean_col
     or position(clean_col in lower(t.title)) > 0
   )
   and (
     (t.student_profile_id is not null and t.student_profile_id = p.id)
     or (t.student_profile_id is null and t.student_id is not null and t.student_id = p.student_id)
     or (t.student_profile_id is null and t.student_name is not null and lower(trim(t.student_name)) = lower(trim(p.full_name)))
   )
  where p.verification_status = 'active'
    and p.role in ('student', 'admin')
    and (p.batch_id = target_batch or p.effective_batch_id = target_batch)
  group by p.id, p.full_name, p.student_id
  order by 
    case when coalesce(sum(case when t.type = 'inflow' then t.amount else 0 end), 0) > 0 then 0 else 1 end asc,
    total_paid desc,
    p.student_id asc nulls last,
    p.full_name asc;
end;
$$;

revoke all on function classmate.batch_fund_payers(uuid, text) from public, anon;
grant execute on function classmate.batch_fund_payers(uuid, text) to authenticated;
