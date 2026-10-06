begin;
-- OAuth account creation precedes the profile RPC; apply the same teacher exception here.
create or replace function classmate.before_user_created(event jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare signup_email text := lower(trim(coalesce(event->'user'->>'email','')));
        provider text := coalesce(event->'user'->'app_metadata'->>'provider','');
begin
  if provider <> 'google' or not (
    signup_email ~ '^[^[:space:]@]+@mbstu[.]ac[.]bd$'
    or (signup_email ~ '^[^[:space:]@]+@gmail[.]com$' and exists (
      select 1 from classmate.teacher_allowlist a where a.email=signup_email and a.active
    ))
  ) then
    return jsonb_build_object('error',jsonb_build_object('http_code',403,
      'message','Use a university Google account or an admin-approved teacher Gmail.'));
  end if;
  return '{}'::jsonb;
end;
$$;
revoke all on function classmate.before_user_created(jsonb) from public,anon,authenticated;
grant execute on function classmate.before_user_created(jsonb) to supabase_auth_admin;
commit;
