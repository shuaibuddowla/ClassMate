begin;

-- Dashboard: Authentication > Hooks > Before User Created > Postgres function.
-- This hook prevents non-institutional or non-Google users from being created.
-- The profile RPC independently checks the confirmed email and auth.identities.
create function classmate.before_user_created(event jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare email text; provider text;
begin
  email := lower(coalesce(event->'user'->>'email', ''));
  provider := coalesce(event->'user'->'app_metadata'->>'provider', '');
  if email !~ '^[^@]+@mbstu[.]ac[.]bd$' or provider <> 'google' then
    return jsonb_build_object('error', jsonb_build_object('http_code', 403,
      'message', 'A university Google account is required.'));
  end if;
  return '{}'::jsonb;
end;
$$;
revoke all on function classmate.before_user_created(jsonb) from public, anon, authenticated;
grant execute on function classmate.before_user_created(jsonb) to supabase_auth_admin;

commit;
