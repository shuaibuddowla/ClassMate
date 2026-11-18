-- Migration: Sort batch_fund_collections by latest modified / transacted activity
drop function if exists classmate.batch_fund_collections(uuid);

create or replace function classmate.batch_fund_collections(
  target_batch uuid
)
returns table(
  title text,
  payment_count bigint,
  total_amount numeric,
  last_transacted_at date,
  last_modified_at timestamptz
)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch fund access denied' using errcode='42501';
  end if;

  return query
  select 
    classmate.canonical_fund_title(t.title) as title,
    count(*)::bigint as payment_count,
    coalesce(sum(t.amount), 0)::numeric as total_amount,
    max(t.transacted_at) as last_transacted_at,
    max(greatest(t.updated_at, t.created_at)) as last_modified_at
  from classmate.batch_fund_transactions t
  where t.batch_id = target_batch
    and t.type = 'inflow'
    and t.title is not null
    and trim(t.title) <> ''
  group by classmate.canonical_fund_title(t.title)
  order by max(greatest(t.updated_at, t.created_at)) desc nulls last, max(t.transacted_at) desc nulls last, count(*) desc, title asc;
end;
$$;

revoke all on function classmate.batch_fund_collections(uuid) from public, anon;
grant execute on function classmate.batch_fund_collections(uuid) to authenticated;
