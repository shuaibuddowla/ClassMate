-- Migration: 202611260003_fix_poll_security_and_unvote.sql
-- Description:
--   1. Secure classmate.get_poll_voters with batch read authorization and anonymous poll protection.
--   2. Explicitly allow empty selected_option_ids on multiple-choice polls in cast_poll_vote so users can unvote cleanly.

begin;

-- 1. Secure get_poll_voters
create or replace function classmate.get_poll_voters(target_poll uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    poll_row classmate.polls%rowtype;
    result jsonb;
begin
    if not classmate.is_active() then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    select * into poll_row from classmate.polls where id = target_poll;
    if not found then
        return '[]'::jsonb;
    end if;

    if not classmate.can_read_batch(poll_row.batch_id) then
        raise exception 'You do not have access to this poll' using errcode = '42501';
    end if;

    if poll_row.is_anonymous then
        raise exception 'Voter identities are hidden for anonymous polls' using errcode = '42501';
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
                        'avatar_url', case
                            when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
                            then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture')
                            else null
                        end,
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

revoke all on function classmate.get_poll_voters(uuid) from public, anon;
grant execute on function classmate.get_poll_voters(uuid) to authenticated;

-- 2. Make cast_poll_vote explicitly handle multi-choice unvoting (empty array) vs single-choice
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
  selected_count integer := coalesce(array_length(selected_option_ids, 1), 0);
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

  if selected_option_ids is null then
    raise exception 'Selected options cannot be null' using errcode = '22023';
  end if;

  if not poll_row.is_multiple_choice and selected_count <> 1 then
    raise exception 'This poll requires exactly one choice' using errcode = '22023';
  end if;

  -- Verify all options belong to this poll
  if selected_count > 0 and exists (
    select 1 from unnest(selected_option_ids) oid
    where not exists (select 1 from classmate.poll_options where id = oid and poll_id = target_poll)
  ) then
    raise exception 'One or more invalid poll options selected' using errcode = '22023';
  end if;

  -- Delete previous votes by this user for this poll
  delete from classmate.poll_votes
  where poll_id = target_poll and voter_id = caller_id;

  -- Insert new votes
  if selected_count > 0 then
    foreach opt_id in array selected_option_ids
    loop
      insert into classmate.poll_votes (poll_id, option_id, voter_id)
      values (target_poll, opt_id, caller_id)
      on conflict (poll_id, voter_id, option_id) do nothing;
    end loop;
  end if;

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

notify pgrst, 'reload schema';

commit;
