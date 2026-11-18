-- Migration: 202611180007_notice_feed_details_attachment_urls.sql
-- Description: Add direct public CDN url to attachments in classmate.notice_feed_details and public.notice_feed_details

begin;

create or replace function classmate.notice_feed_details(target_ids uuid[])
returns jsonb
language sql stable security definer set search_path = '' as $$
with permitted as materialized (
 select n.id, n.author_id, n.resource_id from classmate.notices n
 where n.id = any(target_ids[1:100])
   and (
     auth.uid() is null
     or classmate.is_owner()
     or n.author_id = auth.uid()
     or classmate.can_read_notice(n.id)
     or exists (select 1 from classmate.profiles p where p.id = auth.uid() and p.role = 'admin')
   )
), engagement as (
 select n.id notice_id,
 (select count(*) from classmate.notice_reactions r where r.notice_id=n.id and r.liked) like_count,
 (select count(*) from classmate.notice_comments c where c.notice_id=n.id and c.deleted_at is null) comment_count,
 coalesce(own.liked,false) is_liked,coalesce(own.pinned,false) is_pinned,own.reminder_at
 from permitted n left join classmate.notice_reactions own on own.notice_id=n.id and own.profile_id=(select auth.uid())
), authors as (
 select n.id notice_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') author_name,
 coalesce(
   nullif(trim(p.avatar_url), ''),
   case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
 ) as avatar_url
 from permitted n join classmate.profiles p on p.id=n.author_id left join auth.users u on u.id=p.id
), reads as (
 select n.id notice_id,
 (select count(*) from classmate.notice_reads r where r.notice_id=n.id and not exists (
   select 1 from classmate.owner_preferences op where op.profile_id = r.profile_id and op.read_receipts_enabled = false
 )) read_count,
 exists(select 1 from classmate.notice_reads r where r.notice_id=n.id and r.profile_id=(select auth.uid())) read_by_me from permitted n
), previews as (
 select n.id notice_id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') reader_name,
 coalesce(
   nullif(trim(p.avatar_url), ''),
   case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
 ) as avatar_url,r.read_at
 from permitted n cross join lateral (
   select profile_id,read_at from classmate.notice_reads r0
   where r0.notice_id=n.id
     and not exists (
       select 1 from classmate.owner_preferences op where op.profile_id = r0.profile_id and op.read_receipts_enabled = false
     )
   order by r0.read_at desc,r0.profile_id limit 4
 ) r
 join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
), polls as (
  select
    p.notice_id,
    p.id as poll_id,
    p.question,
    p.is_multiple_choice,
    p.is_anonymous,
    p.expires_at,
    (p.expires_at is not null and p.expires_at < now()) as is_closed,
    (select count(distinct voter_id) from classmate.poll_votes pv where pv.poll_id = p.id) as total_voters,
    (select count(*) from classmate.poll_votes pv where pv.poll_id = p.id) as total_votes,
    (
      select jsonb_agg(
        jsonb_build_object(
          'id', opt.id,
          'option_text', opt.option_text,
          'position', opt.position,
          'votes_count', opt.votes_count,
          'is_my_vote', exists(
            select 1 from classmate.poll_votes pv
            where pv.poll_id = p.id and pv.option_id = opt.id and pv.voter_id = (select auth.uid())
          )
        ) order by opt.position asc
      )
      from classmate.poll_options opt
      where opt.poll_id = p.id
    ) as options
  from permitted n
  join classmate.polls p on p.notice_id = n.id
), attachments as (
  select
    a.notice_id,
    a.id,
    a.storage_key,
    a.file_name,
    a.mime_type,
    a.size_bytes,
    a.created_at,
    concat('https://cbskpifgbdbgdmnnxfrh.supabase.co/storage/v1/object/public/notice-images/', a.storage_key) as url
  from permitted n
  join public.notice_attachments a on a.notice_id = n.id
  order by a.created_at asc
), resources as (
  select
    n.id as notice_id,
    f.id as resource_id,
    f.slug,
    f.title,
    f.file_type,
    f.size_bytes,
    f.mime_type,
    f.link_url,
    coalesce(sc.title_override, c.course_title) as course_title,
    coalesce(sc.code_override, c.course_code) as course_code
  from permitted n
  join classmate.file_metadata f on f.id = n.resource_id
  left join classmate.semester_courses sc on sc.id = f.semester_course_id
  left join classmate.courses c on c.id = sc.course_id
  where f.status = 'active'
)
select jsonb_build_object(
 'engagement',coalesce((select jsonb_agg(to_jsonb(e)) from engagement e),'[]'::jsonb),
 'authors',coalesce((select jsonb_agg(to_jsonb(a)) from authors a),'[]'::jsonb),
 'reads',coalesce((select jsonb_agg(to_jsonb(r)) from reads r),'[]'::jsonb),
 'previews',coalesce((select jsonb_agg(to_jsonb(p)) from previews p),'[]'::jsonb),
 'polls',coalesce((select jsonb_agg(to_jsonb(pl)) from polls pl),'[]'::jsonb),
 'attachments',coalesce((select jsonb_agg(to_jsonb(att)) from attachments att),'[]'::jsonb),
 'resources',coalesce((select jsonb_agg(to_jsonb(res)) from resources res),'[]'::jsonb)
);
$$;

create or replace function public.notice_feed_details(target_ids uuid[])
returns jsonb
language sql stable security definer set search_path = '' as $$
  select classmate.notice_feed_details(target_ids);
$$;

grant execute on function classmate.notice_feed_details(uuid[]) to anon, authenticated, service_role;
grant execute on function public.notice_feed_details(uuid[]) to anon, authenticated, service_role;

notify pgrst, 'reload schema';

commit;
