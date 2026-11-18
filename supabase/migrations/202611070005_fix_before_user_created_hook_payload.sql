-- 202611070005_fix_before_user_created_hook_payload.sql
-- Fix: Supabase Auth Before User Created hook delivers payload under event->'user'
-- Returning '{}'::jsonb allows user creation, returning error jsonb rejects with friendly 403.

create or replace function classmate.before_user_created(event jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_email text;
  v_provider text;
  v_domain text;
  v_is_univ boolean;
begin
  v_email := lower(trim(coalesce(event->'user'->>'email', event->'record'->>'email', '')));
  v_provider := coalesce(event->'user'->'app_metadata'->>'provider', event->'record'->'app_metadata'->>'provider', '');

  if v_email = '' then
    return jsonb_build_object('error', jsonb_build_object('http_code', 400, 'message', 'Email is required.'));
  end if;

  if v_provider <> 'google' and v_provider <> '' then
    return jsonb_build_object('error', jsonb_build_object('http_code', 403, 'message', 'Google sign-in is required.'));
  end if;

  v_domain := lower(split_part(v_email, '@', 2));

  -- Check if domain matches any active institution in database (e.g. ru.ac.bd, mbstu.ac.bd)
  select exists (
    select 1 from classmate.institutions
    where (email_domain = v_domain or v_domain like '%.' || email_domain)
      and is_active = true
  ) into v_is_univ;

  if not v_is_univ and not (
    v_email ~ '^[^[:space:]@]+@gmail[.]com$'
    and exists (
      select 1 from classmate.teacher_allowlist
      where email = v_email and active = true
    )
  ) then
    return jsonb_build_object('error', jsonb_build_object('http_code', 403,
      'message', 'Use a recognized university Google account or an admin-approved teacher Gmail.'));
  end if;

  return '{}'::jsonb;
end;
$$;

revoke all on function classmate.before_user_created(jsonb) from public, anon, authenticated;
grant execute on function classmate.before_user_created(jsonb) to supabase_auth_admin;
grant execute on function classmate.before_user_created(jsonb) to postgres;
