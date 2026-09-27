-- Let the trusted notification worker re-check V2 notice permissions using
-- the caller's Firebase-backed Supabase JWT, instead of legacy Firestore roles.
create or replace function public.can_send_v2_batch_notification(target_batch_code text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_university uuid;
  resolved_batch uuid;
  resolved_department uuid;
begin
  if not public.is_active_user() then
    return false;
  end if;

  select p.university_id into caller_university
  from public.profiles p
  where p.id = public.current_profile_id();

  select b.id, b.department_id into resolved_batch, resolved_department
  from public.batches b
  join public.departments d on d.id = b.department_id
  where d.university_id = caller_university
    and regexp_replace(lower(d.code || b.cohort_code), '[^a-z0-9]', '', 'g')
      = regexp_replace(lower(target_batch_code), '[^a-z0-9]', '', 'g')
    and not b.is_archived
  limit 1;

  return resolved_batch is not null
    and public.can_manage_notice_target(null, resolved_department, resolved_batch, null, null);
end;
$$;

revoke all on function public.can_send_v2_batch_notification(text) from public;
grant execute on function public.can_send_v2_batch_notification(text) to authenticated;
