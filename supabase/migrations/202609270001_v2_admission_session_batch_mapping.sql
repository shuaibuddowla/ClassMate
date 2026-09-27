begin;

create or replace function public.batch_code_from_admission_session(session_code text)
returns text
language plpgsql
immutable
strict
set search_path = ''
as $$
begin
  if session_code !~ '^[0-9]{2}$' then
    raise exception 'Admission session code must contain exactly two digits';
  end if;

  return lpad(mod(session_code::integer + 97, 100)::text, 2, '0');
end;
$$;

revoke all on function public.batch_code_from_admission_session(text) from public;

create or replace function public.bootstrap_firebase_profile()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  token_firebase_uid text := public.current_firebase_uid();
  normalized_email extensions.citext := lower(auth.jwt() ->> 'email');
  normalized_domain extensions.citext;
  identity_parts text[];
  derived_batch_code text;
  matched_university public.universities%rowtype;
  matched_department public.departments%rowtype;
  matched_batch public.batches%rowtype;
  allowed_staff public.staff_allowlist%rowtype;
  profile_state public.profile_status := 'blocked';
  resolved_profile_id uuid;
begin
  if token_firebase_uid is null then
    raise exception 'A verified Firebase identity is required';
  end if;

  if normalized_email is null
     or coalesce((auth.jwt() ->> 'email_verified')::boolean, false) is not true then
    raise exception 'A verified email address is required';
  end if;

  if coalesce(auth.jwt() -> 'firebase' ->> 'sign_in_provider', '') <> 'google.com' then
    raise exception 'Google authentication is required';
  end if;

  normalized_domain := split_part(normalized_email::text, '@', 2)::extensions.citext;
  select * into matched_university
  from public.universities u
  where u.email_domain = normalized_domain and u.is_active
  limit 1;

  if matched_university.id is null then
    raise exception 'University email domain is not authorized';
  end if;

  identity_parts := regexp_match(
    normalized_email::text,
    '^([a-z]+)([0-9]{2})([0-9]{3})@' || replace(matched_university.email_domain::text, '.', '[.]') || '$'
  );

  if identity_parts is not null then
    derived_batch_code := public.batch_code_from_admission_session(identity_parts[2]);
  end if;

  select * into allowed_staff
  from public.staff_allowlist a
  where a.email = normalized_email and a.is_active
  limit 1;

  if allowed_staff.id is not null then
    profile_state := 'active';
  elsif identity_parts is not null then
    select * into matched_department
    from public.departments d
    where d.university_id = matched_university.id
      and d.email_prefix = identity_parts[1]::extensions.citext
      and d.is_active
    limit 1;

    if matched_department.id is not null then
      select * into matched_batch
      from public.batches b
      where b.department_id = matched_department.id
        and b.cohort_code = derived_batch_code
        and not b.is_archived
      limit 1;
    end if;

    profile_state := case
      when matched_batch.id is not null then 'active'::public.profile_status
      else 'pending_setup'::public.profile_status
    end;
  end if;

  insert into public.profiles (
    firebase_uid, university_id, email, display_name, avatar_url, status
  ) values (
    token_firebase_uid,
    matched_university.id,
    normalized_email,
    coalesce(auth.jwt() ->> 'name', normalized_email::text),
    auth.jwt() ->> 'picture',
    profile_state
  )
  on conflict (firebase_uid) do update set
    email = excluded.email,
    display_name = excluded.display_name,
    avatar_url = excluded.avatar_url,
    updated_at = now()
  returning id into resolved_profile_id;

  if allowed_staff.id is not null and allowed_staff.role = 'admin' then
    insert into public.role_grants (
      profile_id, role, department_id
    )
    select resolved_profile_id, allowed_staff.role, allowed_staff.department_id
    where not exists (
      select 1
      from public.role_grants g
      where g.profile_id = resolved_profile_id
        and g.role = allowed_staff.role
        and g.department_id is not distinct from allowed_staff.department_id
        and g.revoked_at is null
    );
  elsif identity_parts is not null then
    insert into public.student_profiles (
      profile_id,
      department_id,
      detected_batch_code,
      assigned_batch_id,
      roll_number
    ) values (
      resolved_profile_id,
      matched_department.id,
      derived_batch_code,
      matched_batch.id,
      identity_parts[3]
    )
    on conflict (profile_id) do nothing;
  end if;

  return resolved_profile_id;
end;
$$;

revoke all on function public.bootstrap_firebase_profile() from public;
revoke all on function public.bootstrap_firebase_profile() from anon;
grant execute on function public.bootstrap_firebase_profile() to authenticated;

commit;
