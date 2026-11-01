-- Migration: 202611020001_notice_feed_polling_system.sql
-- Description: Interactive Material 3 Polling System for ClassMate Notices Feed
-- Supports single-choice, multiple-choice, anonymous voting, expiration timestamps, atomic voting RPC, and batch push notifications.

begin;

-- 1. Add poll_id column to classmate.notices if not present
alter table classmate.notices add column if not exists poll_id uuid;

-- 2. Create classmate.polls table
create table if not exists classmate.polls (
  id uuid primary key default gen_random_uuid(),
  notice_id uuid not null references classmate.notices(id) on delete cascade unique,
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  question text not null check (length(trim(question)) between 2 and 500),
  is_multiple_choice boolean not null default false,
  is_anonymous boolean not null default false,
  expires_at timestamptz,
  created_at timestamptz not null default now()
);

-- Foreign key link back from notices to polls
alter table classmate.notices
  drop constraint if exists notices_poll_id_fkey;
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'notices_poll_id_fkey'
  ) then
    alter table classmate.notices
      add constraint notices_poll_id_fkey
      foreign key (poll_id) references classmate.polls(id) on delete set null;
  end if;
end $$;

-- 3. Create classmate.poll_options table
create table if not exists classmate.poll_options (
  id uuid primary key default gen_random_uuid(),
  poll_id uuid not null references classmate.polls(id) on delete cascade,
  option_text text not null check (length(trim(option_text)) between 1 and 200),
  position integer not null default 0,
  votes_count integer not null default 0 check (votes_count >= 0)
);

-- 4. Create classmate.poll_votes table
create table if not exists classmate.poll_votes (
  id uuid primary key default gen_random_uuid(),
  poll_id uuid not null references classmate.polls(id) on delete cascade,
  option_id uuid not null references classmate.poll_options(id) on delete cascade,
  voter_id uuid not null references classmate.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (poll_id, voter_id, option_id)
);

-- Indexes for lightning fast queries
create index if not exists idx_polls_notice_id on classmate.polls(notice_id);
create index if not exists idx_polls_batch_id on classmate.polls(batch_id);
create index if not exists idx_poll_options_poll on classmate.poll_options(poll_id, position asc);
create index if not exists idx_poll_votes_poll_voter on classmate.poll_votes(poll_id, voter_id);
create index if not exists idx_poll_votes_option on classmate.poll_votes(option_id);

-- Enable RLS
alter table classmate.polls enable row level security;
alter table classmate.poll_options enable row level security;
alter table classmate.poll_votes enable row level security;

-- RLS policies
create policy polls_read on classmate.polls for select to authenticated
  using (classmate.can_read_batch(batch_id));

create policy poll_options_read on classmate.poll_options for select to authenticated
  using (exists (select 1 from classmate.polls p where p.id = poll_options.poll_id and classmate.can_read_batch(p.batch_id)));

create policy poll_votes_read on classmate.poll_votes for select to authenticated
  using (
    exists (
      select 1 from classmate.polls p
      where p.id = poll_votes.poll_id
        and classmate.can_read_batch(p.batch_id)
        and (not p.is_anonymous or poll_votes.voter_id = (select auth.uid()))
    )
  );

-- Realtime replication
do $$
declare
  t text;
begin
  for t in select unnest(array['polls', 'poll_options', 'poll_votes'])
  loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'classmate' and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table classmate.%I', t);
    end if;
  end loop;
end $$;

-- 5. RPC to publish a batch poll
create or replace function classmate.publish_batch_poll(
  target_batch uuid,
  poll_question text,
  poll_options text[],
  poll_description text default '',
  is_multiple_choice boolean default false,
  is_anonymous boolean default false,
  duration_hours integer default 72
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  dept_id uuid;
  notice_row classmate.notices%rowtype;
  new_poll_id uuid;
  opt_text text;
  opt_pos integer := 0;
  calculated_expiry timestamptz;
  clean_question text := trim(poll_question);
  clean_body text := trim(coalesce(poll_description, ''));
begin
  if not classmate.can_post(target_batch, null) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;

  if length(clean_question) < 2 or length(clean_question) > 300 then
    raise exception 'Poll question must be between 2 and 300 characters' using errcode = '22023';
  end if;

  if array_length(poll_options, 1) is null or array_length(poll_options, 1) < 2 then
    raise exception 'Poll must contain at least 2 options' using errcode = '22023';
  end if;

  if array_length(poll_options, 1) > 10 then
    raise exception 'Poll cannot exceed 10 options' using errcode = '22023';
  end if;

  if duration_hours is not null and duration_hours > 0 then
    calculated_expiry := now() + (duration_hours || ' hours')::interval;
  end if;

  select department_id into dept_id from classmate.batches where id = target_batch;
  if dept_id is null then
    raise exception 'Invalid target batch';
  end if;

  -- Insert Notice into feed (triggers automatic notification_outbox push!)
  insert into classmate.notices (
    department_id,
    batch_id,
    author_id,
    title,
    body,
    published_at
  ) values (
    dept_id,
    target_batch,
    caller_id,
    clean_question,
    clean_body,
    now()
  ) returning * into notice_row;

  -- Create Poll row
  insert into classmate.polls (
    notice_id,
    batch_id,
    question,
    is_multiple_choice,
    is_anonymous,
    expires_at
  ) values (
    notice_row.id,
    target_batch,
    clean_question,
    is_multiple_choice,
    is_anonymous,
    calculated_expiry
  ) returning id into new_poll_id;

  -- Link back to notice
  update classmate.notices set poll_id = new_poll_id where id = notice_row.id;

  -- Insert Options
  foreach opt_text in array poll_options
  loop
    if length(trim(opt_text)) > 0 then
      insert into classmate.poll_options (poll_id, option_text, position, votes_count)
      values (new_poll_id, trim(opt_text), opt_pos, 0);
      opt_pos := opt_pos + 1;
    end if;
  end loop;

  return jsonb_build_object(
    'notice_id', notice_row.id,
    'poll_id', new_poll_id,
    'question', clean_question,
    'is_multiple_choice', is_multiple_choice,
    'is_anonymous', is_anonymous,
    'expires_at', calculated_expiry
  );
end;
$$;

revoke all on function classmate.publish_batch_poll(uuid, text, text[], text, boolean, boolean, integer) from public, anon;
grant execute on function classmate.publish_batch_poll(uuid, text, text[], text, boolean, boolean, integer) to authenticated;

-- 6. RPC to cast a poll vote
create or replace function classmate.cast_poll_vote(
  target_poll uuid,
  selected_option_ids uuid[]
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  poll_row classmate.polls%rowtype;
  opt_id uuid;
  total_votes_count integer;
  total_voters_count integer;
  options_json jsonb;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  select * into poll_row from classmate.polls where id = target_poll;
  if poll_row.id is null then
    raise exception 'Poll not found' using errcode = 'P0002';
  end if;

  if not classmate.can_read_batch(poll_row.batch_id) then
    raise exception 'You do not have access to this poll' using errcode = '42501';
  end if;

  if poll_row.expires_at is not null and poll_row.expires_at < now() then
    raise exception 'This poll has already closed' using errcode = '22023';
  end if;

  if selected_option_ids is null or array_length(selected_option_ids, 1) < 1 then
    raise exception 'At least one option must be selected' using errcode = '22023';
  end if;

  if not poll_row.is_multiple_choice and array_length(selected_option_ids, 1) > 1 then
    raise exception 'This poll allows only a single choice' using errcode = '22023';
  end if;

  -- Verify all options belong to this poll
  if exists (
    select 1 from unnest(selected_option_ids) oid
    where not exists (select 1 from classmate.poll_options where id = oid and poll_id = target_poll)
  ) then
    raise exception 'One or more invalid poll options selected' using errcode = '22023';
  end if;

  -- Delete previous votes by this user for this poll
  delete from classmate.poll_votes
  where poll_id = target_poll and voter_id = caller_id;

  -- Insert new votes
  foreach opt_id in array selected_option_ids
  loop
    insert into classmate.poll_votes (poll_id, option_id, voter_id)
    values (target_poll, opt_id, caller_id)
    on conflict (poll_id, voter_id, option_id) do nothing;
  end loop;

  -- Recalculate votes_count atomically on poll_options
  update classmate.poll_options po
  set votes_count = (
    select count(*) from classmate.poll_votes pv
    where pv.option_id = po.id
  )
  where po.poll_id = target_poll;

  select count(distinct voter_id), count(*)
  into total_voters_count, total_votes_count
  from classmate.poll_votes
  where poll_id = target_poll;

  select jsonb_agg(
    jsonb_build_object(
      'id', po.id,
      'option_text', po.option_text,
      'position', po.position,
      'votes_count', po.votes_count,
      'is_my_vote', exists(
        select 1 from classmate.poll_votes pv
        where pv.poll_id = target_poll and pv.option_id = po.id and pv.voter_id = caller_id
      )
    ) order by po.position asc
  ) into options_json
  from classmate.poll_options po
  where po.poll_id = target_poll;

  return jsonb_build_object(
    'poll_id', target_poll,
    'notice_id', poll_row.notice_id,
    'question', poll_row.question,
    'is_multiple_choice', poll_row.is_multiple_choice,
    'is_anonymous', poll_row.is_anonymous,
    'expires_at', poll_row.expires_at,
    'is_closed', (poll_row.expires_at is not null and poll_row.expires_at < now()),
    'total_voters', total_voters_count,
    'total_votes', total_votes_count,
    'options', coalesce(options_json, '[]'::jsonb)
  );
end;
$$;

revoke all on function classmate.cast_poll_vote(uuid, uuid[]) from public, anon;
grant execute on function classmate.cast_poll_vote(uuid, uuid[]) to authenticated;

-- 7. Update notice_feed_details to include polls
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
)
select jsonb_build_object(
 'engagement',coalesce((select jsonb_agg(to_jsonb(e)) from engagement e),'[]'::jsonb),
 'authors',coalesce((select jsonb_agg(to_jsonb(a)) from authors a),'[]'::jsonb),
 'reads',coalesce((select jsonb_agg(to_jsonb(r)) from reads r),'[]'::jsonb),
 'previews',coalesce((select jsonb_agg(to_jsonb(p)) from previews p),'[]'::jsonb),
 'polls',coalesce((select jsonb_agg(to_jsonb(pl)) from polls pl),'[]'::jsonb));
$$;

revoke all on function classmate.notice_feed_details(uuid[]) from public, anon;
grant execute on function classmate.notice_feed_details(uuid[]) to authenticated;

notify pgrst, 'reload schema';

commit;
