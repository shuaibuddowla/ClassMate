begin;

-- Publish the batch notice and its timetable changes together. A failure in
-- either part rolls the whole request back, so the feed and schedule agree.
create function public.publish_class_cancellation(
  target_batch_code text,
  target_offering uuid,
  target_date date,
  notice_title text,
  notice_body text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_university uuid;
  resolved_batch uuid;
  resolved_department uuid;
  resolved_offering uuid;
  created_notice uuid;
  affected_slots integer;
begin
  if not public.is_active_user() then
    raise exception 'An active account is required' using errcode = '42501';
  end if;
  if target_date is null or target_date < current_date then
    raise exception 'Choose today or a future class date' using errcode = '22023';
  end if;
  if char_length(btrim(notice_title)) not between 1 and 200
     or char_length(coalesce(notice_body, '')) > 5000 then
    raise exception 'The notice title or body is invalid' using errcode = '22023';
  end if;

  select p.university_id into caller_university
  from public.profiles p where p.id = public.current_profile_id();

  select b.id, b.department_id into resolved_batch, resolved_department
  from public.batches b
  join public.departments d on d.id = b.department_id
  where d.university_id = caller_university
    and regexp_replace(lower(d.code || b.cohort_code), '[^a-z0-9]', '', 'g')
      = regexp_replace(lower(target_batch_code), '[^a-z0-9]', '', 'g')
    and not b.is_archived
  limit 1;

  if resolved_batch is null then
    raise exception 'The selected batch is not configured' using errcode = 'P0002';
  end if;

  select o.id into resolved_offering
  from public.course_offerings o
  join public.batch_semesters bs on bs.id = o.batch_semester_id
  join public.batches b on b.id = bs.batch_id
  where o.id = target_offering
    and bs.batch_id = resolved_batch
    and bs.state = 'published'
    and b.active_batch_semester_id = bs.id;

  if resolved_offering is null then
    raise exception 'Choose a course in the published semester for this batch' using errcode = '22023';
  end if;
  if not public.can_manage_notice_target(null, resolved_department, resolved_batch, null, null)
     or not public.can_manage_academic_scope(null, null, null, resolved_offering) then
    raise exception 'You cannot cancel this batch class' using errcode = '42501';
  end if;

  select count(*) into affected_slots
  from public.routine_slots r
  where r.course_offering_id = resolved_offering
    and r.weekday = extract(dow from target_date)::smallint
    and r.deleted_at is null;
  if affected_slots = 0 then
    raise exception 'No period is scheduled for this course on the selected date' using errcode = 'P0002';
  end if;
  if exists (
    select 1 from public.class_changes cc
    join public.routine_slots r on r.id = cc.routine_slot_id
    where r.course_offering_id = resolved_offering
      and r.weekday = extract(dow from target_date)::smallint
      and r.deleted_at is null
      and cc.effective_date = target_date
      and cc.kind = 'cancelled'
      and cc.deleted_at is null
  ) then
    raise exception 'This class has already been cancelled for the selected date' using errcode = '23505';
  end if;

  insert into public.notices (author_id, title, body, priority, state, published_at)
  values (public.current_profile_id(), btrim(notice_title), coalesce(notice_body, ''),
          'urgent', 'published', now())
  returning id into created_notice;

  insert into public.notice_targets (notice_id, batch_id)
  values (created_notice, resolved_batch);

  insert into public.class_changes
    (routine_slot_id, notice_id, effective_date, kind, reason, created_by)
  select r.id, created_notice, target_date, 'cancelled', nullif(btrim(notice_body), ''),
         public.current_profile_id()
  from public.routine_slots r
  where r.course_offering_id = resolved_offering
    and r.weekday = extract(dow from target_date)::smallint
    and r.deleted_at is null;

  return jsonb_build_object('id', created_notice, 'cancelled_periods', affected_slots);
end;
$$;

revoke all on function public.publish_class_cancellation(text, uuid, date, text, text) from public, anon;
grant execute on function public.publish_class_cancellation(text, uuid, date, text, text) to authenticated;

-- Preserve the existing feed fields and expose the cancellation marker from
-- its linked timetable change. The app uses this to keep its notice styling.
drop function public.list_visible_notices();

create function public.list_visible_notices()
returns table (
  id uuid,
  author_id uuid,
  author_name text,
  title text,
  body text,
  priority public.notice_priority,
  globally_pinned boolean,
  published_at timestamptz,
  created_at timestamptz,
  updated_at timestamptz,
  like_count bigint,
  is_liked boolean,
  is_personally_pinned boolean,
  is_cancellation boolean,
  subject text
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    n.id,
    n.author_id,
    coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1)),
    n.title,
    n.body,
    n.priority,
    n.globally_pinned,
    n.published_at,
    n.created_at,
    n.updated_at,
    (select count(*) from public.notice_likes nl where nl.notice_id = n.id),
    exists (
      select 1 from public.notice_likes nl
      where nl.notice_id = n.id and nl.profile_id = public.current_profile_id()
    ),
    exists (
      select 1 from public.notice_pins np
      where np.notice_id = n.id and np.profile_id = public.current_profile_id()
    ),
    cancellation.subject is not null,
    cancellation.subject
  from public.notices n
  join public.profiles p on p.id = n.author_id
  left join lateral (
    select c.name as subject
    from public.class_changes cc
    join public.routine_slots r on r.id = cc.routine_slot_id
    join public.course_offerings o on o.id = r.course_offering_id
    join public.courses c on c.id = o.course_id
    where cc.notice_id = n.id and cc.kind = 'cancelled' and cc.deleted_at is null
    limit 1
  ) cancellation on true
  where public.is_active_user()
    and n.state = 'published'
    and n.deleted_at is null
    and public.can_read_notice(n.id)
  order by n.globally_pinned desc, n.published_at desc nulls last, n.created_at desc
  limit 100;
$$;

revoke all on function public.list_visible_notices() from public, anon;
grant execute on function public.list_visible_notices() to authenticated;

commit;
