begin;

-- The Worker passes this decision to the Archive API only after validating
-- the Firebase session. The Archive API still checks ownership and batch.
create or replace function public.can_access_v2_archive_batch(
  target_batch_code text,
  target_action text
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  resolved_batch uuid;
  resolved_department uuid;
begin
  if target_action not in ('read', 'course_create', 'upload', 'resource_admin', 'resource_manage')
     or not public.is_active_user() then
    return false;
  end if;

  select b.id, b.department_id into resolved_batch, resolved_department
  from public.batches b
  join public.departments d on d.id = b.department_id
  join public.profiles p on p.university_id = d.university_id
  where p.id = public.current_profile_id()
    and regexp_replace(lower(d.code || b.cohort_code), '[^a-z0-9]', '', 'g')
      = regexp_replace(lower(target_batch_code), '[^a-z0-9]', '', 'g')
    and not b.is_archived
  limit 1;

  if resolved_batch is null then return false; end if;

  return case target_action
    when 'read' then public.can_access_batch(resolved_batch)
    when 'course_create' then public.can_access_batch(resolved_batch)
    when 'upload' then public.can_manage_academic_scope(
      resolved_department, resolved_batch, null, null
    )
    when 'resource_manage' then public.can_manage_academic_scope(
      resolved_department, resolved_batch, null, null
    )
    when 'resource_admin' then public.has_active_role('admin', null, null, null, null)
    else false
  end;
end;
$$;

revoke all on function public.can_access_v2_archive_batch(text, text) from public, anon;
grant execute on function public.can_access_v2_archive_batch(text, text) to authenticated;

commit;
