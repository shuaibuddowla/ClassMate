-- Client actions operate only on the authenticated profile and a notice that
-- profile can already read. Definer functions avoid exposing profile IDs.

create or replace function public.set_notice_like(target_notice uuid, liked boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_active_user() or not public.can_read_notice(target_notice) then
    raise exception 'Notice is not available to this account' using errcode = '42501';
  end if;
  if liked then
    insert into public.notice_likes (notice_id, profile_id)
    values (target_notice, public.current_profile_id())
    on conflict (notice_id, profile_id) do nothing;
  else
    delete from public.notice_likes
    where notice_id = target_notice and profile_id = public.current_profile_id();
  end if;
end;
$$;

create or replace function public.set_notice_personal_pin(target_notice uuid, pinned boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_active_user() or not public.can_read_notice(target_notice) then
    raise exception 'Notice is not available to this account' using errcode = '42501';
  end if;
  if pinned then
    insert into public.notice_pins (notice_id, profile_id)
    values (target_notice, public.current_profile_id())
    on conflict (notice_id, profile_id) do nothing;
  else
    delete from public.notice_pins
    where notice_id = target_notice and profile_id = public.current_profile_id();
  end if;
end;
$$;

create or replace function public.set_notice_global_pin(target_notice uuid, pinned boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.can_manage_notice(target_notice) then
    raise exception 'Notice pinning is not authorized' using errcode = '42501';
  end if;
  update public.notices
  set globally_pinned = pinned, updated_at = now()
  where id = target_notice and deleted_at is null;
  if not found then
    raise exception 'Notice was not found' using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function public.set_notice_like(uuid, boolean) from public;
revoke all on function public.set_notice_personal_pin(uuid, boolean) from public;
revoke all on function public.set_notice_global_pin(uuid, boolean) from public;
grant execute on function public.set_notice_like(uuid, boolean) to authenticated;
grant execute on function public.set_notice_personal_pin(uuid, boolean) to authenticated;
grant execute on function public.set_notice_global_pin(uuid, boolean) to authenticated;

