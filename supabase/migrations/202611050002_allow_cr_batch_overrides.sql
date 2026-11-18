-- ============================================================================
-- Migration: Allow CR and Admins to manage and view Batch Overrides
-- ============================================================================

-- 1. Update owner_batch_overrides to allow CRs to see their batch overrides
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
  -- If caller is server owner or admin: return all overrides
  if classmate.is_owner() or exists (
    select 1 from classmate.profiles p
    where p.id = (select auth.uid()) and p.verification_status = 'active'
      and p.role = 'admin'
  ) then
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
    return;
  end if;

  -- If caller is a Class Representative (CR): return overrides for batches they manage
  if exists (
    select 1 from classmate.profiles p
    where p.id = (select auth.uid()) and p.verification_status = 'active'
      and p.role = 'student' and p.is_cr
      and (p.cr_valid_until is null or p.cr_valid_until > now())
  ) then
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
    where classmate.can_manage_batch(o.effective_batch_id)
    order by o.created_at desc;
    return;
  end if;

  raise exception 'Unauthorized to view batch overrides' using errcode = '42501';
end $$;

-- 2. Update set_batch_override to allow CRs to add overrides for their batch
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
  if not classmate.can_manage_batch(target_batch) then
    raise exception 'Unauthorized to manage overrides for this batch' using errcode = '42501';
  end if;

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

-- 3. Update delete_batch_override to allow CRs to remove overrides for their batch
create or replace function classmate.delete_batch_override(
  target_student_id text default null,
  target_override_id uuid default null
)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  norm_id text := upper(trim(coalesce(target_student_id, '')));
  existing_row classmate.batch_overrides%rowtype;
  deleted_row classmate.batch_overrides%rowtype;
begin
  if target_override_id is not null then
    select * into existing_row from classmate.batch_overrides where id = target_override_id;
  elsif norm_id <> '' then
    select * into existing_row from classmate.batch_overrides where student_id = norm_id;
  else
    raise exception 'Either target_student_id or target_override_id must be provided' using errcode = '22023';
  end if;

  if existing_row.id is null then
    raise exception 'Batch override not found' using errcode = '22023';
  end if;

  if not classmate.can_manage_batch(existing_row.effective_batch_id) then
    raise exception 'Unauthorized to delete override for this batch' using errcode = '42501';
  end if;

  delete from classmate.batch_overrides
  where id = existing_row.id
  returning * into deleted_row;

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

-- 4. Update RLS policy on batch_overrides
drop policy if exists batch_overrides_owner on classmate.batch_overrides;
drop policy if exists batch_overrides_select on classmate.batch_overrides;
create policy batch_overrides_select on classmate.batch_overrides for select to authenticated
  using (classmate.is_owner() or classmate.can_manage_batch(effective_batch_id));

-- 5. Permissions
grant execute on function classmate.set_batch_override(text,uuid,text),
  classmate.delete_batch_override(text,uuid),
  classmate.owner_batch_overrides() to authenticated;
