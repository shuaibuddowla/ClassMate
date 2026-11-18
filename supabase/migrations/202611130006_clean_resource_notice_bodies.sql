-- Migration: 202611130006_clean_resource_notice_bodies.sql
-- Description: Clean notice bodies for resources to remove raw URLs and show "A new resource has been shared for [Course Name]. Open the file below."

begin;

-- 1. Update sync_resource_notice() for uploaded files
create or replace function classmate.sync_resource_notice()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  resource_title text;
  v_course_title text;
  v_body text;
begin
  if old.status = 'pending' and new.status = 'active' then
    resource_title := left('Resource: ' || new.title, 200);

    if new.semester_course_id is not null then
      select coalesce(nullif(trim(sc.title_override), ''), nullif(trim(c.course_title), ''))
      into v_course_title
      from classmate.semester_courses sc
      left join classmate.courses c on c.id = sc.course_id
      where sc.id = new.semester_course_id;
    end if;

    if v_course_title is not null and trim(v_course_title) <> '' then
      v_body := 'A new resource has been shared for ' || trim(v_course_title) || '. Open the file below.';
    else
      v_body := 'A new resource has been shared. Open the file below.';
    end if;

    insert into classmate.notices(department_id, batch_id, semester_course_id,
      author_id, title, body, resource_id)
    values (new.department_id, new.batch_id, new.semester_course_id,
      new.uploaded_by, resource_title, v_body, new.id);
  elsif old.status in ('active', 'deleting') and new.status = 'archived' then
    delete from classmate.notification_outbox o
      using classmate.notices n
      where n.resource_id = new.id and o.kind = 'notice' and o.record_id = n.id;
    delete from classmate.notices where resource_id = new.id;
  end if;
  return new;
end;
$$;

-- 2. Update create_library_link() for shared links (Drive links, etc.)
create or replace function classmate.create_library_link(
  target_batch uuid,
  target_course uuid,
  target_title text,
  target_category text default 'notes',
  target_url text default null
)
returns classmate.file_metadata
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  v_dept uuid;
  v_title text := trim(target_title);
  v_url text := trim(target_url);
  v_cat text := coalesce(nullif(trim(target_category), ''), 'notes');
  v_course_title text;
  v_body text;
  new_resource classmate.file_metadata;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if not classmate.can_post(target_batch, target_course) then
    raise exception 'Permission denied to share resource' using errcode = '42501';
  end if;

  if v_title is null or length(v_title) not between 1 and 200 then
    raise exception 'Title must be between 1 and 200 characters' using errcode = '23514';
  end if;

  if v_url is null or length(v_url) not between 5 and 2000 then
    raise exception 'Valid URL required' using errcode = '23514';
  end if;

  -- Ensure scheme is http or https
  if not (v_url ~* '^https?://') then
    v_url := 'https://' || v_url;
  end if;

  if v_cat not in ('notes', 'slides', 'questions', 'syllabus', 'other') then
    v_cat := 'notes';
  end if;

  select department_id into v_dept from classmate.batches where id = target_batch;
  if v_dept is null then
    raise exception 'Batch not found' using errcode = 'P0002';
  end if;

  if target_course is not null then
    if not exists (
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id
      where sc.id = target_course and s.batch_id = target_batch and s.status = 'active'
    ) then
      raise exception 'Course is not active in this batch' using errcode = '23514';
    end if;

    select coalesce(nullif(trim(sc.title_override), ''), nullif(trim(c.course_title), ''))
    into v_course_title
    from classmate.semester_courses sc
    left join classmate.courses c on c.id = sc.course_id
    where sc.id = target_course;
  end if;

  if v_course_title is not null and trim(v_course_title) <> '' then
    v_body := 'A new resource has been shared for ' || trim(v_course_title) || '. Open the file below.';
  else
    v_body := 'A new resource has been shared. Open the file below.';
  end if;

  insert into classmate.file_metadata (
    department_id,
    batch_id,
    semester_course_id,
    uploaded_by,
    title,
    category,
    file_type,
    r2_object_key,
    mime_type,
    size_bytes,
    status,
    link_url
  ) values (
    v_dept,
    target_batch,
    target_course,
    caller_id,
    v_title,
    v_cat,
    'link',
    null,
    'text/uri-list',
    0,
    'active',
    v_url
  ) returning * into new_resource;

  -- Generate in-app feed notice so batch mates see the resource notice in their feed
  insert into classmate.notices (
    department_id,
    batch_id,
    semester_course_id,
    author_id,
    title,
    body,
    resource_id
  ) values (
    v_dept,
    target_batch,
    target_course,
    caller_id,
    left('Resource: ' || v_title, 200),
    v_body,
    new_resource.id
  );

  insert into classmate.audit_log (actor_id, action, target_id, details)
  values (caller_id, 'create_library_link', new_resource.id, jsonb_build_object('title', v_title, 'url', v_url));

  return new_resource;
end;
$$;

revoke all on function classmate.create_library_link(uuid, uuid, text, text, text) from public, anon;
grant execute on function classmate.create_library_link(uuid, uuid, text, text, text) to authenticated;

-- 3. Backfill all existing resource notices to remove raw URLs and show course context
update classmate.notices n
set body = case
  when coalesce(nullif(trim(sc.title_override), ''), nullif(trim(c.course_title), '')) is not null then
    'A new resource has been shared for ' || trim(coalesce(nullif(trim(sc.title_override), ''), trim(c.course_title))) || '. Open the file below.'
  else
    'A new resource has been shared. Open the file below.'
end
from classmate.file_metadata f
left join classmate.semester_courses sc on sc.id = f.semester_course_id
left join classmate.courses c on c.id = sc.course_id
where n.resource_id = f.id;

notify pgrst, 'reload schema';

commit;
