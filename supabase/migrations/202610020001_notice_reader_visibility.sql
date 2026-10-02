begin;
drop function classmate.notice_readers(uuid);
create function classmate.notice_readers(target_notice uuid)
returns table(profile_id uuid, reader_name text, read_at timestamptz, avatar_url text)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not coalesce(classmate.can_read_notice(target_notice), false) then
    raise exception 'Notice access required' using errcode = '42501';
  end if;
  return query select r.profile_id,
    coalesce(nullif(trim(p.full_name), ''), 'ClassMate member'), r.read_at,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') else null end
  from classmate.notice_reads r join classmate.profiles p on p.id = r.profile_id
  left join auth.users u on u.id = p.id
  where r.notice_id = target_notice order by r.read_at desc, r.profile_id;
end;
$$;
revoke all on function classmate.notice_readers(uuid) from public, anon;
grant execute on function classmate.notice_readers(uuid) to authenticated;

create function classmate.notice_reader_previews(target_ids uuid[])
returns table(notice_id uuid, profile_id uuid, reader_name text, avatar_url text, read_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select ranked.notice_id, ranked.profile_id, ranked.reader_name, ranked.avatar_url, ranked.read_at
  from (
    select r.notice_id, r.profile_id,
      coalesce(nullif(trim(p.full_name), ''), 'ClassMate member') as reader_name,
      case when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
        then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') else null end as avatar_url,
      r.read_at, row_number() over (partition by r.notice_id order by r.read_at desc, r.profile_id) as position
    from classmate.notice_reads r join classmate.profiles p on p.id = r.profile_id
    left join auth.users u on u.id = p.id
    where r.notice_id = any(target_ids[1:100]) and classmate.can_read_notice(r.notice_id)
  ) ranked where ranked.position <= 4 order by ranked.notice_id, ranked.position;
$$;
revoke all on function classmate.notice_reader_previews(uuid[]) from public, anon;
grant execute on function classmate.notice_reader_previews(uuid[]) to authenticated;
notify pgrst, 'reload schema';
commit;
