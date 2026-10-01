begin;

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
    when 'resource_manage' then
      public.can_manage_academic_scope(resolved_department, resolved_batch, null, null)
      or exists (
        select 1
        from public.role_grants g
        join public.course_offerings o on o.id = g.course_offering_id
        join public.batch_semesters bs on bs.id = o.batch_semester_id
        where g.profile_id = public.current_profile_id()
          and g.role = 'teacher' and g.revoked_at is null
          and g.starts_at <= now() and (g.expires_at is null or g.expires_at > now())
          and bs.batch_id = resolved_batch
      )
    when 'resource_admin' then public.has_active_role('admin', null, null, null, null)
    else false
  end;
end;
$$;

revoke all on function public.can_access_v2_archive_batch(text, text) from public, anon;
grant execute on function public.can_access_v2_archive_batch(text, text) to authenticated;

commit;
