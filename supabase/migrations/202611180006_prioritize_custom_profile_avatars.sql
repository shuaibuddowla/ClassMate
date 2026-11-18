begin;

-- 1. Update classmate.notice_feed_details:
-- Coalesce p.avatar_url first (custom uploaded avatar), then fallback to Google OAuth picture in raw_user_meta_data.
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
    a.created_at
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

-- 2. Update classmate.notice_readers_page:
create or replace function classmate.notice_readers_page(target_notice uuid,before_time timestamptz default null,before_id uuid default null,page_size integer default 50)
returns table(profile_id uuid,reader_name text,read_at timestamptz,avatar_url text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not coalesce(classmate.can_read_notice(target_notice),false) then raise exception 'Notice access required' using errcode='42501'; end if;
  if (before_time is null)<>(before_id is null) then raise exception 'Both cursor values are required'; end if;
  return query select r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),r.read_at,
    coalesce(
      nullif(trim(p.avatar_url), ''),
      case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    ) as avatar_url
    from classmate.notice_reads r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
    where r.notice_id=target_notice
      and not exists (
        select 1 from classmate.owner_preferences op
        where op.profile_id = r.profile_id and op.read_receipts_enabled = false
      )
      and (before_time is null or r.read_at<before_time or (r.read_at=before_time and r.profile_id>before_id))
    order by r.read_at desc,r.profile_id limit least(greatest(page_size,1),50);
end $$;

-- 3. Update classmate.notice_reader_previews:
create or replace function classmate.notice_reader_previews(target_ids uuid[])
returns table(notice_id uuid,profile_id uuid,reader_name text,avatar_url text,read_at timestamptz)
language sql stable security definer set search_path='' as $$
  select n.id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),
    coalesce(
      nullif(trim(p.avatar_url), ''),
      case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    ) as avatar_url,r.read_at
  from classmate.notices n cross join lateral (
    select r0.profile_id,r0.read_at from classmate.notice_reads r0
    where r0.notice_id=n.id
      and not exists (
        select 1 from classmate.owner_preferences op
        where op.profile_id = r0.profile_id and op.read_receipts_enabled = false
      )
    order by r0.read_at desc,r0.profile_id limit 4
  ) r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id;
$$;

-- 4. Update classmate.get_poll_voters:
create or replace function classmate.get_poll_voters(target_poll uuid)
returns jsonb
language plpgsql
security definer
set search_path = classmate, public, auth
as $$
declare
    poll_row classmate.polls%rowtype;
    result jsonb;
begin
    select * into poll_row from classmate.polls where id = target_poll;
    if not found then
        return '[]'::jsonb;
    end if;

    select coalesce(jsonb_agg(
        jsonb_build_object(
            'option_id', po.id,
            'option_text', po.option_text,
            'position', po.position,
            'votes_count', po.votes_count,
            'voters', coalesce((
                select jsonb_agg(
                    jsonb_build_object(
                        'voter_id', p.id,
                        'name', coalesce(nullif(trim(p.full_name), ''), 'Student'),
                        'avatar_url', coalesce(
                            nullif(trim(p.avatar_url), ''),
                            case 
                                when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://' 
                                then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') 
                                else null 
                            end
                        ),
                        'student_id', coalesce(p.student_id, ''),
                        'voted_at', pv.created_at
                    ) order by pv.created_at desc
                )
                from classmate.poll_votes pv
                join classmate.profiles p on p.id = pv.voter_id
                left join auth.users u on u.id = p.id
                where pv.option_id = po.id
            ), '[]'::jsonb)
        ) order by po.position asc
    ), '[]'::jsonb) into result
    from classmate.poll_options po
    where po.poll_id = target_poll;

    return result;
end;
$$;

-- 5. Update classmate.batch_friends:
drop function if exists classmate.batch_friends(uuid,text,integer);
create or replace function classmate.batch_friends(target_batch uuid,query_text text default '',result_offset integer default 0)
returns table(profile_id uuid,full_name text,student_id text,role text,is_cr boolean,avatar_url text,is_online boolean,blood_group text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'This batch directory is unavailable' using errcode='42501'; end if;
  if result_offset<0 or result_offset is null or length(query_text)>100 then raise exception 'Invalid directory query' using errcode='22023'; end if;
  return query select p.id,p.full_name,p.student_id,p.role::text,p.is_cr,
    coalesce(
      nullif(trim(p.avatar_url), ''),
      case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
        then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    ),
    exists(select 1 from classmate.active_presence ap where ap.profile_id=p.id and ap.last_seen_at > now() - interval '60 seconds'),
    p.blood_group
    from classmate.profiles p join auth.users u on u.id=p.id
    where p.verification_status='active' and (
      (p.role='student' and p.batch_id=target_batch and p.department_id=(select department_id from classmate.batches where id=target_batch))
      or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.active and o.email=p.email)
        and exists(select 1 from classmate.batches b join classmate.departments d on d.id=b.department_id
          where b.id=target_batch and (
            p.batch_id=b.id or (p.batch_id is null
              and p.email ~ '^[a-z]+[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$'
              and d.email_prefix=substring(p.email from '^([a-z]+)[0-9]')
              and b.academic_session=substring(p.email from '^[a-z]+([0-9]{2})')::integer
              and b.batch_number=b.academic_session-d.session_offset)))))
      and position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')||' '||coalesce(p.blood_group,'')||' '||replace(coalesce(p.blood_group,''),'+',' positive')||' '||replace(coalesce(p.blood_group,''),'-',' negative')))>0
    order by
      (p.role='admin') desc,
      case when p.student_id is not null and trim(p.student_id) <> '' and p.student_id <> 'null' then 0 else 1 end,
      p.student_id asc,
      p.full_name asc,
      p.id asc
    limit 100 offset result_offset;
end $$;

-- 6. Update classmate.comment_page:
create or replace function classmate.comment_page(target_notice uuid,target_parent uuid default null,
  before_time timestamptz default null,before_id uuid default null) returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_notice(target_notice) then
    raise exception 'Notice access denied' using errcode='42501'; end if;
  return coalesce((select jsonb_agg(row_value order by stamp desc,id desc) from (
    select c.created_at stamp,c.id,jsonb_build_object('id',c.id,'parent_id',c.parent_id,'body',c.body,
      'author_id',c.author_id,'author_name',p.full_name,'created_at',c.created_at,'updated_at',c.updated_at,
      'avatar_url',coalesce(
        nullif(trim(p.avatar_url), ''),
        case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
          then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
      ),
      'like_count',(select count(*) from classmate.comment_likes l where l.comment_id=c.id),
      'liked_by_me',exists(select 1 from classmate.comment_likes l where l.comment_id=c.id and l.profile_id=auth.uid()),
      'reply_count',(select count(*) from classmate.notice_comments r where r.notice_id=c.notice_id and r.parent_id=c.id and r.deleted_at is null),
      'can_edit',c.author_id=auth.uid(),'can_delete',c.author_id=auth.uid() or classmate.is_owner()) row_value
    from classmate.notice_comments c join classmate.profiles p on p.id=c.author_id left join auth.users u on u.id=p.id
    where c.notice_id=target_notice and c.parent_id is not distinct from target_parent and c.deleted_at is null
      and (target_parent is null or exists(select 1 from classmate.notice_comments par where par.id=target_parent and par.notice_id=target_notice and par.parent_id is null and par.deleted_at is null))
      and (before_time is null or (c.created_at,c.id)<(before_time,before_id))
    order by c.created_at desc,c.id desc limit 50) q),'[]'::jsonb);
end $$;

grant execute on function classmate.notice_feed_details(uuid[]) to authenticated, anon;
grant execute on function classmate.notice_readers_page(uuid,timestamptz,uuid,integer) to authenticated;
grant execute on function classmate.notice_reader_previews(uuid[]) to authenticated;
grant execute on function classmate.get_poll_voters(uuid) to authenticated;
grant execute on function classmate.batch_friends(uuid,text,integer) to authenticated;
grant execute on function classmate.comment_page(uuid,uuid,timestamptz,uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
