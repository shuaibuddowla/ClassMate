-- Run as database owner after replacing the placeholder. Never ship the real
-- owner email in Android, a tracked migration, or a client-side setting.
begin;
do $$
declare owner_email text := lower('CHANGE_ME@mbstu.ac.bd');
begin
  if owner_email = 'change_me@mbstu.ac.bd'
    or owner_email !~ '^[^@]+@mbstu[.]ac[.]bd$' then
    raise exception 'Replace the owner email before bootstrapping';
  end if;
  if exists (select 1 from classmate.app_owners where active and email <> owner_email) then
    raise exception 'An active owner already exists';
  end if;
  insert into classmate.app_owners(email, active)
  values (owner_email, true)
  on conflict (email) do update set active = true;
end $$;
commit;
