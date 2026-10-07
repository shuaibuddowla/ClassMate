begin;

-- Re-add / Dropout Student Override
-- Maps a student_id (e.g. 'CE23045') to an effective_batch_id to override
-- email-derived admission session cohorts for students with semester drops/repeats.

create table if not exists classmate.batch_overrides (
  id uuid primary key default gen_random_uuid(),
  student_id text not null check (length(trim(student_id)) between 4 and 30),
  effective_batch_id uuid not null references classmate.batches(id) on delete restrict,
  reason text,
  added_by uuid not null references classmate.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (student_id)
);

alter table classmate.batch_overrides enable row level security;
revoke all on classmate.batch_overrides from public, anon;
grant select on classmate.batch_overrides to authenticated;
grant all on classmate.batch_overrides to service_role;

create policy batch_overrides_owner on classmate.batch_overrides for select to authenticated
  using (classmate.is_owner());

-- Ensure effective_batch_id is explicitly available on profiles
alter table classmate.profiles
  add column if not exists effective_batch_id uuid generated always as (batch_id) stored;

-- Atomic RPC to set an override and update any existing profile live
create or replace function classmate.set_batch_override(
  target_student_id text,
  target_batch uuid,
  target_reason text default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  norm_id text := upper(trim(coalesce(target_student_id, '')));
  batch_row classmate.batches%rowtype;
  override_row classmate.batch_overrides%rowtype;
  profile_updated boolean := false;
  matched_profile classmate.profiles%rowtype;
begin
  perform classmate.require_owner();

  if length(norm_id) not between 4 and 30 or norm_id !~ '^[A-Za-z0-9-]{4,30}$' then
    raise exception 'Enter a valid student ID (e.g. CE23045)' using errcode = '22023';
  end if;

  select * into batch_row from classmate.batches where id = target_batch and is_active;
  if batch_row.id is null then
    raise exception 'Target batch not found or inactive' using errcode = '22023';
  end if;

  insert into classmate.batch_overrides (student_id, effective_batch_id, reason, added_by, created_at)
  values (norm_id, batch_row.id, nullif(trim(target_reason), ''), auth.uid(), now())
  on conflict (student_id) do update set
    effective_batch_id = excluded.effective_batch_id,
    reason = excluded.reason,
    added_by = excluded.added_by,
    created_at = now()
  returning * into override_row;

  -- Check if student already has a registered profile
  select * into matched_profile from classmate.profiles
  where upper(trim(student_id)) = norm_id and role = 'student' for update;

  if matched_profile.id is not null then
    update classmate.profiles
    set batch_id = batch_row.id,
        department_id = batch_row.department_id,
        academic_session = batch_row.academic_session,
        verification_status = 'active',
        rejection_reason = null,
        is_cr = case when is_cr and cr_batch_id is distinct from batch_row.id then false else is_cr end,
        cr_batch_id = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_batch_id end,
        cr_valid_until = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_valid_until end,
        updated_at = now()
    where id = matched_profile.id;
    profile_updated := true;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'set_batch_override',
    batch_row.id,
    jsonb_build_object(
      'student_id', norm_id,
      'effective_batch_id', batch_row.id,
      'reason', override_row.reason,
      'profile_updated', profile_updated,
      'profile_id', matched_profile.id
    )
  );

  return jsonb_build_object(
    'id', override_row.id,
    'student_id', override_row.student_id,
    'effective_batch_id', override_row.effective_batch_id,
    'reason', override_row.reason,
    'created_at', override_row.created_at,
    'profile_updated', profile_updated
  );
end $$;

-- RPC to delete an override
create or replace function classmate.delete_batch_override(
  target_student_id text default null,
  target_override_id uuid default null
)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  norm_id text := upper(trim(coalesce(target_student_id, '')));
  deleted_row classmate.batch_overrides%rowtype;
begin
  perform classmate.require_owner();

  if target_override_id is not null then
    delete from classmate.batch_overrides
    where id = target_override_id
    returning * into deleted_row;
  elsif norm_id <> '' then
    delete from classmate.batch_overrides
    where student_id = norm_id
    returning * into deleted_row;
  else
    raise exception 'Either target_student_id or target_override_id must be provided' using errcode = '22023';
  end if;

  if deleted_row.id is null then
    raise exception 'Batch override not found' using errcode = '22023';
  end if;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'delete_batch_override',
    deleted_row.effective_batch_id,
    jsonb_build_object(
      'student_id', deleted_row.student_id,
      'deleted_override_id', deleted_row.id
    )
  );

  return jsonb_build_object(
    'student_id', deleted_row.student_id,
    'deleted', true
  );
end $$;

-- RPC to list all overrides for owner/admin
create or replace function classmate.owner_batch_overrides()
returns table(
  id uuid,
  student_id text,
  effective_batch_id uuid,
  batch_number smallint,
  academic_session smallint,
  department_code text,
  reason text,
  added_by uuid,
  created_at timestamptz
) language plpgsql stable security definer set search_path = '' as $$
begin
  perform classmate.require_owner();
  return query
  select
    o.id,
    o.student_id,
    o.effective_batch_id,
    b.batch_number,
    b.academic_session,
    d.code as department_code,
    o.reason,
    o.added_by,
    o.created_at
  from classmate.batch_overrides o
  join classmate.batches b on b.id = o.effective_batch_id
  join classmate.departments d on d.id = b.department_id
  order by o.created_at desc;
end $$;

revoke all on function classmate.set_batch_override(text,uuid,text),
  classmate.delete_batch_override(text,uuid),
  classmate.owner_batch_overrides() from public, anon;
grant execute on function classmate.set_batch_override(text,uuid,text),
  classmate.delete_batch_override(text,uuid),
  classmate.owner_batch_overrides() to authenticated;

-- Integrated complete_student_onboarding checking batch_overrides first
create or replace function classmate.complete_student_onboarding(
  selected_department uuid, entered_student_id text default null,
  entered_batch_number smallint default null, entered_session smallint default null
) returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare
  p classmate.profiles%rowtype;
  d classmate.departments%rowtype;
  matched text[];
  derived_session smallint;
  derived_batch smallint;
  resolved_batch uuid;
  parsed_student_id text;
  override_batch_id uuid;
  override_session smallint;
  override_dept_id uuid;
begin
  p := classmate.create_or_initialize_profile();
  if p.role <> 'student' then raise exception 'Student onboarding only' using errcode = '42501'; end if;
  if p.department_id is not null then raise exception 'Onboarding already submitted'; end if;
  select * into d from classmate.departments where id = selected_department and is_active;
  if d.id is null then raise exception 'Department unavailable'; end if;

  if d.email_prefix is not null then
    matched := regexp_match(p.email, '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if matched is null or matched[1] <> d.email_prefix then
      raise exception 'Institutional email does not match department';
    end if;

    parsed_student_id := upper(matched[1] || matched[2] || matched[3]);

    -- Check for an administrative batch override first
    select o.effective_batch_id, b.academic_session, b.department_id
    into override_batch_id, override_session, override_dept_id
    from classmate.batch_overrides o
    join classmate.batches b on b.id = o.effective_batch_id
    where upper(o.student_id) = parsed_student_id and b.is_active;

    if override_batch_id is not null then
      update classmate.profiles set department_id = override_dept_id, batch_id = override_batch_id,
        student_id = parsed_student_id, academic_session = override_session, requested_batch_number = null,
        profile_source = 'auto', verification_status = 'active', rejection_reason = null,
        updated_at = now() where id = p.id returning * into p;
    else
      derived_session := matched[2]::smallint;
      derived_batch := derived_session - d.session_offset;
      if derived_batch < 0 then raise exception 'Invalid department session offset'; end if;
      insert into classmate.batches(department_id, batch_number, academic_session)
      values (d.id, derived_batch, derived_session)
      on conflict (department_id, batch_number) do nothing;
      select id into resolved_batch from classmate.batches
        where department_id = d.id and batch_number = derived_batch
          and academic_session = derived_session and is_active;
      if resolved_batch is null then raise exception 'Batch/session conflict'; end if;
      update classmate.profiles set department_id = d.id, batch_id = resolved_batch,
        student_id = parsed_student_id,
        academic_session = derived_session, requested_batch_number = null,
        profile_source = 'auto', verification_status = 'active', rejection_reason = null,
        updated_at = now() where id = p.id returning * into p;
    end if;
  else
    if entered_student_id is null or trim(entered_student_id) !~ '^[A-Za-z0-9-]{4,30}$'
      or entered_batch_number is null or entered_batch_number not between 0 and 99
      or entered_session is null or entered_session not between 0 and 99 then
      raise exception 'Student ID, batch, and session are required';
    end if;

    parsed_student_id := upper(trim(entered_student_id));

    select o.effective_batch_id, b.academic_session, b.department_id
    into override_batch_id, override_session, override_dept_id
    from classmate.batch_overrides o
    join classmate.batches b on b.id = o.effective_batch_id
    where upper(o.student_id) = parsed_student_id and b.is_active;

    if override_batch_id is not null then
      update classmate.profiles set department_id = override_dept_id, batch_id = override_batch_id,
        student_id = parsed_student_id, academic_session = override_session, requested_batch_number = null,
        profile_source = 'auto', verification_status = 'active', rejection_reason = null,
        updated_at = now() where id = p.id returning * into p;
    else
      update classmate.profiles set department_id = d.id, batch_id = null,
        student_id = parsed_student_id, academic_session = entered_session,
        requested_batch_number = entered_batch_number, profile_source = 'manual',
        verification_status = 'pending', rejection_reason = null, updated_at = now()
      where id = p.id returning * into p;
    end if;
  end if;

  return p;
end;
$$;

notify pgrst, 'reload schema';
commit;
