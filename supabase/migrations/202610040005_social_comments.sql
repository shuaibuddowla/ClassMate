begin;
alter table classmate.notice_comments add column updated_at timestamptz;
alter table classmate.notice_comments add column client_request uuid;
create unique index notice_comment_request on classmate.notice_comments(author_id,client_request) where client_request is not null;
create index notice_comment_threads on classmate.notice_comments(notice_id,parent_id,created_at desc,id desc) where deleted_at is null;
create table classmate.comment_likes (
  comment_id uuid not null references classmate.notice_comments(id) on delete cascade,
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  created_at timestamptz not null default now(), primary key(comment_id,profile_id)
);
alter table classmate.comment_likes enable row level security;
revoke all on classmate.comment_likes from public,anon,authenticated;

create function classmate.comment_page(target_notice uuid,target_parent uuid default null,
  before_time timestamptz default null,before_id uuid default null) returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_notice(target_notice) then
    raise exception 'Notice access denied' using errcode='42501'; end if;
  return coalesce((select jsonb_agg(row_value order by stamp desc,id desc) from (
    select c.created_at stamp,c.id,jsonb_build_object('id',c.id,'parent_id',c.parent_id,'body',c.body,
      'author_id',c.author_id,'author_name',p.full_name,'created_at',c.created_at,'updated_at',c.updated_at,
      'avatar_url',case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
        then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,
      'like_count',(select count(*) from classmate.comment_likes l where l.comment_id=c.id),
      'liked_by_me',exists(select 1 from classmate.comment_likes l where l.comment_id=c.id and l.profile_id=auth.uid()),
      'reply_count',(select count(*) from classmate.notice_comments r where r.notice_id=c.notice_id and r.parent_id=c.id and r.deleted_at is null),
      'can_edit',c.author_id=auth.uid(),'can_delete',c.author_id=auth.uid() or classmate.is_owner()) row_value
    from classmate.notice_comments c join classmate.profiles p on p.id=c.author_id join auth.users u on u.id=p.id
    where c.notice_id=target_notice and c.parent_id is not distinct from target_parent and c.deleted_at is null
      and (target_parent is null or exists(select 1 from classmate.notice_comments par where par.id=target_parent and par.notice_id=target_notice and par.parent_id is null and par.deleted_at is null))
      and (before_time is null or (c.created_at,c.id)<(before_time,before_id))
    order by c.created_at desc,c.id desc limit 50) q),'[]'::jsonb);
end $$;

create function classmate.save_notice_comment(target_notice uuid,target_body text,target_parent uuid default null,
  target_id uuid default null,target_request uuid default null) returns classmate.notice_comments
language plpgsql security definer set search_path='' as $$
declare saved classmate.notice_comments%rowtype;
begin
  if not classmate.is_active() or not classmate.can_read_notice(target_notice) then raise exception 'Notice access denied' using errcode='42501'; end if;
  if length(trim(target_body)) not between 1 and 2000 then raise exception 'Write a comment within 2,000 characters'; end if;
  if target_parent is not null and not exists(select 1 from classmate.notice_comments p
      where p.id=target_parent and p.notice_id=target_notice and p.parent_id is null and p.deleted_at is null) then
    raise exception 'Reply to an available comment in this notice'; end if;
  if target_id is null then
    insert into classmate.notice_comments(notice_id,author_id,parent_id,body,client_request)
    values(target_notice,auth.uid(),target_parent,trim(target_body),target_request)
    on conflict(author_id,client_request) where client_request is not null do nothing returning * into saved;
    if saved.id is null then select * into saved from classmate.notice_comments where author_id=auth.uid() and client_request=target_request;
      if saved.notice_id<>target_notice or saved.parent_id is distinct from target_parent then raise exception 'Comment request conflict'; end if; end if;
  else
    update classmate.notice_comments set body=trim(target_body),updated_at=now()
      where id=target_id and notice_id=target_notice and author_id=auth.uid() and deleted_at is null returning * into saved;
    if not found then raise exception 'You can edit only your own available comment' using errcode='42501'; end if;
  end if;
  return saved;
end $$;
create function classmate.delete_notice_comment(target_id uuid) returns void
language plpgsql security definer set search_path='' as $$
begin
  update classmate.notice_comments set deleted_at=now() where id=target_id
    and (author_id=auth.uid() or classmate.is_owner()) and classmate.can_read_notice(notice_id);
  if not found then raise exception 'Comment deletion denied' using errcode='42501'; end if;
  update classmate.notice_comments set deleted_at=now() where parent_id=target_id and deleted_at is null;
end $$;
create function classmate.set_comment_like(target_id uuid,target_liked boolean) returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.is_active() or not exists(select 1 from classmate.notice_comments c where c.id=target_id
    and c.deleted_at is null and classmate.can_read_notice(c.notice_id)) then raise exception 'Comment access denied' using errcode='42501'; end if;
  if target_liked then insert into classmate.comment_likes(comment_id,profile_id) values(target_id,auth.uid()) on conflict do nothing;
  else delete from classmate.comment_likes where comment_id=target_id and profile_id=auth.uid(); end if;
end $$;
revoke all on function classmate.comment_page(uuid,uuid,timestamptz,uuid),classmate.save_notice_comment(uuid,text,uuid,uuid,uuid),
  classmate.delete_notice_comment(uuid),classmate.set_comment_like(uuid,boolean) from public,anon;
grant execute on function classmate.comment_page(uuid,uuid,timestamptz,uuid),classmate.save_notice_comment(uuid,text,uuid,uuid,uuid),
  classmate.delete_notice_comment(uuid),classmate.set_comment_like(uuid,boolean) to authenticated;
notify pgrst,'reload schema';
commit;
