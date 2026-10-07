begin;

create table if not exists classmate.owner_preferences (
  profile_id uuid primary key references classmate.profiles(id) on delete cascade,
  read_receipts_enabled boolean not null default true,
  updated_at timestamptz not null default now()
);

alter table classmate.owner_preferences enable row level security;
revoke all on classmate.owner_preferences from public, anon, authenticated;
grant all on classmate.owner_preferences to service_role;

create or replace function classmate.owner_preferences()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not classmate.is_owner() or not classmate.is_active() then
    raise exception 'Admin permission required' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'read_receipts_enabled',
    coalesce((select read_receipts_enabled from classmate.owner_preferences where profile_id = auth.uid()), true)
  );
end $$;

create or replace function classmate.save_owner_preferences(target_read_receipts boolean)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not classmate.is_owner() or not classmate.is_active() then
    raise exception 'Admin permission required' using errcode = '42501';
  end if;
  insert into classmate.owner_preferences(profile_id, read_receipts_enabled, updated_at)
  values (auth.uid(), coalesce(target_read_receipts, true), now())
  on conflict (profile_id) do update
  set read_receipts_enabled = excluded.read_receipts_enabled,
      updated_at = now();

  return classmate.owner_preferences();
end $$;

-- Update mark_notices_read: if the current user is an owner who disabled personal read receipts, skip insertion
create or replace function classmate.mark_notices_read(target_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
declare
  receipts_enabled boolean := true;
begin
  if not classmate.is_active() then
    raise exception 'Active account required' using errcode = '42501';
  end if;

  if classmate.is_owner() then
    select coalesce(read_receipts_enabled, true) into receipts_enabled
    from classmate.owner_preferences where profile_id = auth.uid();
    if receipts_enabled is false then
      return;
    end if;
  end if;

  insert into classmate.notice_reads(notice_id, profile_id)
  select n.id, auth.uid() from classmate.notices n
  where n.id = any(target_ids) and classmate.can_read_notice(n.id)
  on conflict do nothing;
end $$;

revoke all on function classmate.owner_preferences() from public, anon;
grant execute on function classmate.owner_preferences() to authenticated;

revoke all on function classmate.save_owner_preferences(boolean) from public, anon;
grant execute on function classmate.save_owner_preferences(boolean) to authenticated;

notify pgrst, 'reload schema';
commit;
