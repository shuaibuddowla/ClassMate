-- ============================================================================
-- Migration: 202611140001_notice_system_fixes.sql
-- Fixes:
-- 1. classmate.delete_notice: Allow institution admins to delete notices.
-- 2. classmate.edit_notice: Allow institution admins to edit notices.
-- 3. classmate.cast_poll_vote: Allow empty array / retraction of poll votes.
-- 4. classmate.get_public_notice: Include attached poll data in public shared notices.
-- ============================================================================

begin;

-- 1. delete_notice with admin permissions
create or replace function classmate.delete_notice(target_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      join classmate.batches b on b.id = notice_row.batch_id
      join classmate.departments d on d.id = b.department_id
      where p.id = auth.uid()
        and p.role = 'admin'
        and (p.institution_id is null or p.institution_id = d.institution_id)
    )
    or (notice_row.author_id = auth.uid() and classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))
  ) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  delete from classmate.notification_outbox
    where kind = 'notice' and record_id = target_id;
  delete from classmate.notices where id = target_id;
  if notice_row.class_change_id is not null then
    delete from classmate.notification_outbox
      where kind = 'class_change' and record_id = notice_row.class_change_id;
    delete from classmate.class_changes where id = notice_row.class_change_id;
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'delete_notice', target_id);
end;
$$;

revoke all on function classmate.delete_notice(uuid) from public, anon;
grant execute on function classmate.delete_notice(uuid) to authenticated;

-- 2. edit_notice with admin permissions
create or replace function classmate.edit_notice(target_id uuid, target_title text, target_body text)
returns classmate.notices language plpgsql security definer set search_path = '' as $$
declare notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      join classmate.batches b on b.id = notice_row.batch_id
      join classmate.departments d on d.id = b.department_id
      where p.id = auth.uid()
        and p.role = 'admin'
        and (p.institution_id is null or p.institution_id = d.institution_id)
    )
    or (notice_row.author_id = auth.uid() and classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))
  ) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  update classmate.notices set title = trim(target_title), body = coalesce(target_body, '')
    where id = target_id returning * into notice_row;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'edit_notice', target_id);
  return notice_row;
end;
$$;

revoke all on function classmate.edit_notice(uuid, text, text) from public, anon;
grant execute on function classmate.edit_notice(uuid, text, text) to authenticated;

-- 3. cast_poll_vote allowing retraction / zero selections
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

  if not poll_row.is_multiple_choice and array_length(selected_option_ids, 1) > 1 then
    raise exception 'This poll allows only a single choice' using errcode = '22023';
  end if;

  -- Verify all options belong to this poll if any provided
  if selected_option_ids is not null and array_length(selected_option_ids, 1) > 0 then
    if exists (
      select 1 from unnest(selected_option_ids) oid
      where not exists (select 1 from classmate.poll_options where id = oid and poll_id = target_poll)
    ) then
      raise exception 'One or more invalid poll options selected' using errcode = '22023';
    end if;
  end if;

  -- Delete previous votes by this user for this poll
  delete from classmate.poll_votes
  where poll_id = target_poll and voter_id = caller_id;

  -- Insert new votes if any selected
  if selected_option_ids is not null and array_length(selected_option_ids, 1) > 0 then
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

-- 4. get_public_notice with poll support
create or replace function classmate.get_public_notice(target_identifier text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  as_uuid uuid;
  res record;
begin
  if target_identifier is null or trim(target_identifier) = '' then
    return null;
  end if;

  begin
    as_uuid := target_identifier::uuid;
  exception when others then
    return null;
  end;

  select
    n.id,
    n.title,
    n.body,
    n.published_at,
    b.id as batch_id,
    b.batch_number,
    b.academic_session,
    d.id as department_id,
    d.name as department_name,
    d.code as department_code,
    p.id as author_id,
    p.full_name as author_name,
    p.role as author_role,
    p.is_cr as author_is_cr,
    p.avatar_url as author_avatar_url,
    c.title as course_title,
    c.code as course_code,
    (cc.id is not null) as is_cancelled,
    cc.kind as class_change_kind,
    cc.change_date as class_change_date,
    cc.details as class_change_details,
    fm.id as resource_id,
    fm.title as resource_title,
    fm.file_type as resource_file_type,
    fm.size_bytes as resource_size_bytes,
    fm.mime_type as resource_mime_type,
    fm.link_url as resource_link_url,
    fm.is_link as resource_is_link
  into res
  from classmate.notices n
  join classmate.batches b on b.id = n.batch_id
  join classmate.departments d on d.id = b.department_id
  left join classmate.profiles p on p.id = n.author_id
  left join classmate.semester_courses sc on sc.id = n.semester_course_id
  left join classmate.courses c on c.id = sc.course_id
  left join classmate.class_changes cc on cc.id = n.class_change_id
  left join classmate.file_metadata fm on fm.id = (
    select id from classmate.file_metadata f
    where f.batch_id = n.batch_id and (f.course_id = sc.course_id or f.uploader_id = n.author_id)
      and abs(extract(epoch from (f.created_at - n.published_at))) < 120
    order by abs(extract(epoch from (f.created_at - n.published_at))) asc
    limit 1
  )
  where n.id = as_uuid
  limit 1;

  if res.id is null then
    return null;
  end if;

  return jsonb_build_object(
    'id', res.id,
    'title', res.title,
    'body', res.body,
    'published_at', res.published_at,
    'department_id', res.department_id,
    'department_name', res.department_name,
    'department_code', res.department_code,
    'batch_id', res.batch_id,
    'batch_number', res.batch_number,
    'academic_session', res.academic_session,
    'author_id', res.author_id,
    'author_name', res.author_name,
    'author_role', res.author_role,
    'author_is_cr', res.author_is_cr,
    'author_avatar_url', res.author_avatar_url,
    'course_title', res.course_title,
    'course_code', res.course_code,
    'is_cancelled', res.is_cancelled,
    'class_change_kind', res.class_change_kind,
    'class_change_date', res.class_change_date,
    'class_change_details', res.class_change_details,
    'resource_id', res.resource_id,
    'resource_title', res.resource_title,
    'resource_file_type', res.resource_file_type,
    'resource_size_bytes', res.resource_size_bytes,
    'resource_mime_type', res.resource_mime_type,
    'resource_link_url', res.resource_link_url,
    'resource_is_link', res.resource_is_link,
    'poll', (
      select jsonb_build_object(
        'id', p.id,
        'question', p.question,
        'is_multiple_choice', p.is_multiple_choice,
        'is_anonymous', p.is_anonymous,
        'expires_at', p.expires_at,
        'is_closed', (p.expires_at is not null and p.expires_at < now()),
        'total_voters', (select count(distinct voter_id) from classmate.poll_votes pv where pv.poll_id = p.id),
        'total_votes', (select count(*) from classmate.poll_votes pv where pv.poll_id = p.id),
        'options', coalesce((
          select jsonb_agg(
            jsonb_build_object(
              'id', po.id,
              'option_text', po.option_text,
              'position', po.position,
              'votes_count', po.votes_count
            ) order by po.position asc
          ) from classmate.poll_options po where po.poll_id = p.id
        ), '[]'::jsonb)
      )
      from classmate.polls p
      where p.notice_id = res.id
      limit 1
    )
  );
end;
$$;

commit;
