begin;

-- The timetable management screen uses this RPC. Keep its name for existing
-- clients, but remove the owned period permanently rather than hiding it.
create or replace function public.archive_routine_slot(target_slot uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing public.routine_slots%rowtype;
begin
  select * into existing
  from public.routine_slots
  where id = target_slot and deleted_at is null
  for update;

  if existing.id is null then
    raise exception 'Period not found' using errcode = 'P0002';
  end if;
  if existing.created_by <> public.current_profile_id()
     and not public.can_admin_academic_scope(null, null, null, existing.course_offering_id) then
    raise exception 'Not authorized to delete this period' using errcode = '42501';
  end if;

  -- class_changes rows are linked with ON DELETE CASCADE.
  delete from public.routine_slots where id = target_slot;
end;
$$;

revoke all on function public.archive_routine_slot(uuid) from public, anon;
grant execute on function public.archive_routine_slot(uuid) to authenticated;

commit;
