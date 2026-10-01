begin;

-- Called only after the protected R2 object has been removed. Keep the
-- database cleanup atomic so a retry can finish an interrupted deletion.
create function classmate.finalize_resource_deletion(target_resource uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare current_status text;
begin
  select status into current_status from classmate.file_metadata
    where id = target_resource for update;
  if not found then return false; end if;
  if current_status not in ('deleting', 'archived') then
    raise exception 'Resource is not ready for permanent deletion'
      using errcode = '23514';
  end if;

  delete from classmate.notification_outbox o
    using classmate.notices n
    where n.resource_id = target_resource and o.kind = 'notice' and o.record_id = n.id;
  delete from classmate.notification_outbox
    where kind = 'file' and record_id = target_resource;
  delete from classmate.notices where resource_id = target_resource;
  -- file_favorites and notice_reads are removed by their cascading FKs.
  delete from classmate.file_metadata where id = target_resource;
  return true;
end;
$$;
revoke all on function classmate.finalize_resource_deletion(uuid) from public, anon, authenticated;
grant execute on function classmate.finalize_resource_deletion(uuid) to service_role;

commit;
