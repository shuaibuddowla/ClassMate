begin;
-- Test owner read receipts toggle behavior

do $$
declare
  owner_id uuid;
  notice_id uuid;
begin
  select p.id into owner_id from classmate.profiles p
  join classmate.app_owners o on o.email = p.email where o.active and p.role = 'admin' limit 1;

  if owner_id is null then
    raise exception 'Admin fixture missing';
  end if;

  select n.id into notice_id from classmate.notices n limit 1;
  if notice_id is null then
    -- create a temporary notice if none exist
    insert into classmate.notices(title, body) values ('Test notice', 'Test body') returning id into notice_id;
  end if;

  -- Ensure clean state
  delete from classmate.notice_reads where profile_id = owner_id and notice_id = notice_id;
  delete from classmate.owner_preferences where profile_id = owner_id;
end $$;

-- Switch to owner authentication context
select set_config('request.jwt.claim.sub', (
  select p.id::text from classmate.profiles p
  join classmate.app_owners o on o.email = p.email where o.active and p.role = 'admin' limit 1
), true);
set local role authenticated;

-- 1. Default should be enabled
select pg_temp.assert_true(
  (classmate.owner_preferences()->>'read_receipts_enabled')::boolean = true,
  'Default read receipts should be enabled'
);

-- 2. Toggle to disabled
select classmate.save_owner_preferences(false);

select pg_temp.assert_true(
  (classmate.owner_preferences()->>'read_receipts_enabled')::boolean = false,
  'Read receipts should now be disabled'
);

-- 3. mark_notices_read should not insert a row while disabled
do $$
declare
  n_id uuid;
  uid uuid := auth.uid();
begin
  select id into n_id from classmate.notices where classmate.can_read_notice(id) limit 1;
  if n_id is not null then
    perform classmate.mark_notices_read(array[n_id]);
    if exists (select 1 from classmate.notice_reads where notice_id = n_id and profile_id = uid) then
      raise exception 'Notice read receipt was recorded even though toggle is OFF';
    end if;
  end if;
end $$;

-- 4. Toggle back to enabled
select classmate.save_owner_preferences(true);

select pg_temp.assert_true(
  (classmate.owner_preferences()->>'read_receipts_enabled')::boolean = true,
  'Read receipts should now be enabled again'
);

-- 5. mark_notices_read should insert a row while enabled
do $$
declare
  n_id uuid;
  uid uuid := auth.uid();
begin
  select id into n_id from classmate.notices where classmate.can_read_notice(id) limit 1;
  if n_id is not null then
    perform classmate.mark_notices_read(array[n_id]);
    if not exists (select 1 from classmate.notice_reads where notice_id = n_id and profile_id = uid) then
      raise exception 'Notice read receipt was NOT recorded when toggle is ON';
    end if;
  end if;
end $$;

-- 6. Non-owner cannot call owner_preferences
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000099', true);
do $$ begin
  perform classmate.owner_preferences();
  raise exception 'Non-owner should not access owner_preferences';
exception when insufficient_privilege then null;
end $$;

rollback;
