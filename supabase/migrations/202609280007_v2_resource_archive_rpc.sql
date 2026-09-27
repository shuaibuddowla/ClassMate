create or replace function public.archive_academic_resource(target_resource uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing public.resources%rowtype;
begin
  select * into existing
  from public.resources r
  where r.id = target_resource and r.deleted_at is null;

  if existing.id is null then
    raise exception 'Resource not found' using errcode = 'P0002';
  end if;
  if not public.can_manage_academic_scope(null, null, null, existing.course_offering_id) then
    raise exception 'Not authorized to archive this resource' using errcode = '42501';
  end if;

  update public.resources
  set state = 'archived', deleted_at = now(), deleted_by = public.current_profile_id()
  where id = existing.id;
end;
$$;

revoke all on function public.archive_academic_resource(uuid) from public;
grant execute on function public.archive_academic_resource(uuid) to authenticated;
