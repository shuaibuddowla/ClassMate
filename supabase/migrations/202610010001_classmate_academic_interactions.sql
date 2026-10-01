begin;

alter table classmate.file_metadata
  add column category text not null default 'notes'
    check (category in ('notes', 'slides', 'questions', 'syllabus', 'other'));

create table classmate.file_favorites (
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  file_id uuid not null references classmate.file_metadata(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (profile_id, file_id)
);
create index file_favorites_file_idx on classmate.file_favorites(file_id);

create function classmate.can_read_notice(target_notice uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from classmate.notices n
    where n.id = target_notice and classmate.can_read_batch(n.batch_id)
      and (n.semester_course_id is null
        or classmate.can_manage_course(n.semester_course_id)
        or exists (select 1 from classmate.profiles p
          where p.id = (select auth.uid()) and p.role = 'student'
            and p.batch_id = n.batch_id and p.verification_status = 'active'))
  );
$$;
revoke all on function classmate.can_read_notice(uuid) from public, anon;

create table classmate.notice_reactions (
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  notice_id uuid not null references classmate.notices(id) on delete cascade,
  liked boolean not null default false,
  pinned boolean not null default false,
  reminder_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key (profile_id, notice_id)
);
create index notice_reactions_likes_idx on classmate.notice_reactions(notice_id)
  where liked;

create table classmate.notice_comments (
  id uuid primary key default gen_random_uuid(),
  notice_id uuid not null references classmate.notices(id) on delete cascade,
  author_id uuid not null references classmate.profiles(id) on delete cascade,
  parent_id uuid references classmate.notice_comments(id) on delete cascade,
  body text not null check (length(trim(body)) between 1 and 2000),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index notice_comments_feed_idx on classmate.notice_comments(notice_id, created_at);
create function classmate.validate_notice_reply() returns trigger language plpgsql
set search_path = '' as $$
begin
  if new.parent_id is not null and not exists (
    select 1 from classmate.notice_comments p
    where p.id = new.parent_id and p.notice_id = new.notice_id) then
    raise exception 'Reply must belong to the same notice';
  end if;
  return new;
end;
$$;
create trigger validate_notice_reply before insert or update on classmate.notice_comments
  for each row execute function classmate.validate_notice_reply();

alter table classmate.file_favorites enable row level security;
alter table classmate.notice_reactions enable row level security;
alter table classmate.notice_comments enable row level security;

create policy file_favorites_own on classmate.file_favorites for all to authenticated
  using (profile_id = (select auth.uid()) and exists (
    select 1 from classmate.file_metadata f where f.id = file_id))
  with check (profile_id = (select auth.uid()) and exists (
    select 1 from classmate.file_metadata f where f.id = file_id));
create policy notice_reactions_own on classmate.notice_reactions for all to authenticated
  using (profile_id = (select auth.uid()) and classmate.can_read_notice(notice_id))
  with check (profile_id = (select auth.uid()) and classmate.can_read_notice(notice_id));
create policy notice_comments_read on classmate.notice_comments for select to authenticated
  using (classmate.can_read_notice(notice_id));
create policy notice_comments_post on classmate.notice_comments for insert to authenticated
  with check (author_id = (select auth.uid()) and classmate.is_active()
    and classmate.can_read_notice(notice_id));
create policy notice_comments_delete on classmate.notice_comments for delete to authenticated
  using (author_id = (select auth.uid()) or classmate.is_owner());

grant select, insert, update, delete on classmate.file_favorites to authenticated;
grant select, insert, update, delete on classmate.notice_reactions to authenticated;
grant select, insert, delete on classmate.notice_comments to authenticated;
grant execute on function classmate.can_read_notice(uuid) to authenticated;

create function classmate.notice_engagement(target_ids uuid[])
returns table(notice_id uuid, like_count bigint, comment_count bigint,
  is_liked boolean, is_pinned boolean, reminder_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select n.id,
    (select count(*) from classmate.notice_reactions r where r.notice_id = n.id and r.liked),
    (select count(*) from classmate.notice_comments c where c.notice_id = n.id and c.deleted_at is null),
    coalesce(own.liked, false), coalesce(own.pinned, false), own.reminder_at
  from classmate.notices n
  left join classmate.notice_reactions own
    on own.notice_id = n.id and own.profile_id = (select auth.uid())
  where n.id = any(target_ids) and classmate.can_read_notice(n.id);
$$;
revoke all on function classmate.notice_engagement(uuid[]) from public, anon;
grant execute on function classmate.notice_engagement(uuid[]) to authenticated;

commit;
