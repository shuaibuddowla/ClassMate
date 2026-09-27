create or replace function public.set_notice_reminder(target_notice uuid, target_remind_at timestamptz)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if not public.is_active_user() then
    raise exception 'An active account is required' using errcode = '42501';
  end if;
  if target_remind_at <= now() then
    raise exception 'Reminder must be in the future' using errcode = '22023';
  end if;
  if not public.can_read_notice(target_notice) then
    raise exception 'Notice is unavailable' using errcode = '42501';
  end if;

  delete from public.notice_reminders
  where notice_id = target_notice and profile_id = public.current_profile_id();

  insert into public.notice_reminders (notice_id, profile_id, remind_at)
  values (target_notice, public.current_profile_id(), target_remind_at);
end;
$$;

create or replace function public.remove_notice_reminder(target_notice uuid)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  delete from public.notice_reminders
  where notice_id = target_notice and profile_id = public.current_profile_id();
end;
$$;

create or replace function public.list_my_notice_reminders()
returns table (notice_id uuid, remind_at timestamptz)
language sql
stable
security invoker
set search_path = ''
as $$
  select nr.notice_id, nr.remind_at
  from public.notice_reminders nr
  where nr.profile_id = public.current_profile_id()
    and nr.remind_at > now()
    and public.can_read_notice(nr.notice_id)
  order by nr.remind_at;
$$;

revoke all on function public.set_notice_reminder(uuid, timestamptz) from public;
revoke all on function public.remove_notice_reminder(uuid) from public;
revoke all on function public.list_my_notice_reminders() from public;
grant execute on function public.set_notice_reminder(uuid, timestamptz) to authenticated;
grant execute on function public.remove_notice_reminder(uuid) to authenticated;
grant execute on function public.list_my_notice_reminders() to authenticated;
