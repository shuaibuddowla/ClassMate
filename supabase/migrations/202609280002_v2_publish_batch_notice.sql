-- Atomically publish a plain-text notice to an authorized academic batch.
-- Batch routing codes are resolved server-side within the caller's university.

create or replace function public.publish_batch_notice(
  target_batch_code text,
  notice_title text,
  notice_body text,
  notice_priority text default 'normal'
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_university uuid;
  resolved_batch uuid;
  resolved_department uuid;
  created_notice uuid;
  parsed_priority public.notice_priority;
begin
  if not public.is_active_user() then
    raise exception 'An active account is required' using errcode = '42501';
  end if;
  if char_length(btrim(notice_title)) not between 1 and 200 then
    raise exception 'Title must contain 1 to 200 characters' using errcode = '22023';
  end if;
  if char_length(notice_body) > 5000 then
    raise exception 'Body cannot exceed 5000 characters' using errcode = '22023';
  end if;

  begin
    parsed_priority := lower(coalesce(nullif(btrim(notice_priority), ''), 'normal'))::public.notice_priority;
  exception when invalid_text_representation then
    raise exception 'Unsupported notice priority' using errcode = '22023';
  end;

  select p.university_id into caller_university
  from public.profiles p
  where p.id = public.current_profile_id();

  select b.id, b.department_id into resolved_batch, resolved_department
  from public.batches b
  join public.departments d on d.id = b.department_id
  where d.university_id = caller_university
    and lower(regexp_replace(d.code || b.cohort_code, '[^a-z0-9]', '', 'g'))
      = lower(regexp_replace(target_batch_code, '[^a-z0-9]', '', 'g'))
    and not b.is_archived
  limit 1;

  if resolved_batch is null then
    raise exception 'The selected batch is not configured' using errcode = 'P0002';
  end if;
  if not public.can_manage_notice_target(null, resolved_department, resolved_batch, null, null) then
    raise exception 'You cannot post notices to this batch' using errcode = '42501';
  end if;

  insert into public.notices (author_id, title, body, priority, state, published_at)
  values (public.current_profile_id(), btrim(notice_title), coalesce(notice_body, ''), parsed_priority, 'published', now())
  returning id into created_notice;

  insert into public.notice_targets (notice_id, batch_id)
  values (created_notice, resolved_batch);

  return created_notice;
end;
$$;

revoke all on function public.publish_batch_notice(text, text, text, text) from public;
grant execute on function public.publish_batch_notice(text, text, text, text) to authenticated;
