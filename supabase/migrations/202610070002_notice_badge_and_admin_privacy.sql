begin;

-- 1. Update mark_notices_read:
-- Always record notice_reads so unread_activity can accurately know the user (including admin) has read the notice.
-- The privacy filter in notice_feed_details, notice_readers_page, and notice_reader_previews ensures that
-- owners who disabled read receipts are never exposed publicly in reader previews, total seen counts, or the viewer dialog.
create or replace function classmate.mark_notices_read(target_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not classmate.is_active() then
    raise exception 'Active account required' using errcode = '42501';
  end if;

  insert into classmate.notice_reads(notice_id, profile_id)
  select n.id, auth.uid() from classmate.notices n
  where n.id = any(target_ids) and classmate.can_read_notice(n.id)
  on conflict do nothing;
end $$;

-- 2. Update notice_reader_previews:
-- Exclude any reader profile whose owner preferences have read_receipts_enabled = false.
create or replace function classmate.notice_reader_previews(target_ids uuid[])
returns table(notice_id uuid,profile_id uuid,reader_name text,avatar_url text,read_at timestamptz)
language sql stable security definer set search_path='' as $$
  select n.id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,r.read_at
  from classmate.notices n cross join lateral (
    select r0.profile_id,r0.read_at from classmate.notice_reads r0
    where r0.notice_id=n.id
      and not exists (
        select 1 from classmate.owner_preferences op
        where op.profile_id = r0.profile_id and op.read_receipts_enabled = false
      )
    order by r0.read_at desc,r0.profile_id limit 4
  ) r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
  where n.id=any(target_ids[1:100]) and classmate.can_read_notice(n.id);
$$;

-- 3. Update notice_readers_page:
-- Exclude any reader profile whose owner preferences have read_receipts_enabled = false.
create or replace function classmate.notice_readers_page(target_notice uuid,before_time timestamptz default null,before_id uuid default null,page_size integer default 50)
returns table(profile_id uuid,reader_name text,read_at timestamptz,avatar_url text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not coalesce(classmate.can_read_notice(target_notice),false) then raise exception 'Notice access required' using errcode='42501'; end if;
  if (before_time is null)<>(before_id is null) then raise exception 'Both cursor values are required'; end if;
  return query select r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),r.read_at,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    from classmate.notice_reads r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
    where r.notice_id=target_notice
      and not exists (
        select 1 from classmate.owner_preferences op
        where op.profile_id = r.profile_id and op.read_receipts_enabled = false
      )
      and (before_time is null or r.read_at<before_time or (r.read_at=before_time and r.profile_id>before_id))
    order by r.read_at desc,r.profile_id limit least(greatest(page_size,1),50);
end $$;

-- 4. Update notice_feed_details:
-- In reads: exclude owners with read_receipts_enabled = false from the public read_count tally.
-- In previews: exclude owners with read_receipts_enabled = false from avatar previews.
create or replace function classmate.notice_feed_details(target_ids uuid[]) returns jsonb
language sql stable security definer set search_path='' as $$
with permitted as materialized (
 select n.id,n.author_id from classmate.notices n
 where n.id=any(target_ids[1:100]) and classmate.can_read_notice(n.id)
), engagement as (
 select n.id notice_id,
 (select count(*) from classmate.notice_reactions r where r.notice_id=n.id and r.liked) like_count,
 (select count(*) from classmate.notice_comments c where c.notice_id=n.id and c.deleted_at is null) comment_count,
 coalesce(own.liked,false) is_liked,coalesce(own.pinned,false) is_pinned,own.reminder_at
 from permitted n left join classmate.notice_reactions own on own.notice_id=n.id and own.profile_id=(select auth.uid())
), authors as (
 select n.id notice_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') author_name,
 case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end avatar_url
 from permitted n join classmate.profiles p on p.id=n.author_id join auth.users u on u.id=p.id
), reads as (
 select n.id notice_id,
 (select count(*) from classmate.notice_reads r where r.notice_id=n.id and not exists (
   select 1 from classmate.owner_preferences op where op.profile_id = r.profile_id and op.read_receipts_enabled = false
 )) read_count,
 exists(select 1 from classmate.notice_reads r where r.notice_id=n.id and r.profile_id=(select auth.uid())) read_by_me from permitted n
), previews as (
 select n.id notice_id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') reader_name,
 case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end avatar_url,r.read_at
 from permitted n cross join lateral (
   select profile_id,read_at from classmate.notice_reads r0
   where r0.notice_id=n.id
     and not exists (
       select 1 from classmate.owner_preferences op where op.profile_id = r0.profile_id and op.read_receipts_enabled = false
     )
   order by r0.read_at desc,r0.profile_id limit 4
 ) r
 join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
)
select jsonb_build_object(
 'engagement',coalesce((select jsonb_agg(to_jsonb(e)) from engagement e),'[]'::jsonb),
 'authors',coalesce((select jsonb_agg(to_jsonb(a)) from authors a),'[]'::jsonb),
 'reads',coalesce((select jsonb_agg(to_jsonb(r)) from reads r),'[]'::jsonb),
 'previews',coalesce((select jsonb_agg(to_jsonb(p)) from previews p),'[]'::jsonb));
$$;

notify pgrst,'reload schema';
commit;
