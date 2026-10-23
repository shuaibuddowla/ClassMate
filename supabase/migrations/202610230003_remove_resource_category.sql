begin;

-- 1. Ensure file_metadata category column has default 'notes'
alter table classmate.file_metadata alter column category set default 'notes';

-- 2. Update create_library_link to make target_category optional (defaults to 'notes')
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

  -- Generate in-app feed notice so batch mates see the drive link in their feed
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
    'A new learning resource link has been shared. Open the link below.',
    new_resource.id
  );

  insert into classmate.audit_log (actor_id, action, target_id, details)
  values (caller_id, 'create_library_link', new_resource.id, jsonb_build_object('title', v_title, 'url', v_url));

  return new_resource;
end;
$$;

revoke all on function classmate.create_library_link(uuid, uuid, text, text, text) from public, anon;
grant execute on function classmate.create_library_link(uuid, uuid, text, text, text) to authenticated;

-- 3. Update edit_resource_metadata to make target_category optional (defaults to 'notes')
create or replace function classmate.edit_resource_metadata(
  target_resource uuid,
  target_title text,
  target_category text default 'notes',
  target_course uuid default null,
  target_link_url text default null
)
returns classmate.file_metadata
language plpgsql
security definer
set search_path = ''
as $$
declare
  resource classmate.file_metadata;
  updated classmate.file_metadata;
  v_url text;
  v_cat text;
begin
  select * into resource from classmate.file_metadata where id = target_resource and status = 'active' for update;
  if not found then raise exception 'File is no longer available' using errcode = 'P0002'; end if;

  if not (classmate.is_owner() or exists (
    select 1 from classmate.profiles p join classmate.batches b on b.id = p.cr_batch_id
    where p.id = (select auth.uid()) and p.role = 'student' and p.verification_status = 'active'
      and p.is_cr and p.cr_batch_id = resource.batch_id and b.is_active
      and (p.cr_valid_until is null or p.cr_valid_until > now())
  )) then
    raise exception 'Only the owner or an active CR of this batch can edit files' using errcode = '42501';
  end if;

  if target_title is null or length(trim(target_title)) not between 1 and 200 then
    raise exception 'File name must contain 1 to 200 characters' using errcode = '23514';
  end if;

  v_cat := coalesce(nullif(trim(target_category), ''), coalesce(resource.category, 'notes'));
  if v_cat not in ('notes', 'slides', 'questions', 'syllabus', 'other') then
    v_cat := 'notes';
  end if;

  if target_course is null or not exists (
    select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id
    where sc.id = target_course and s.batch_id = resource.batch_id and b.department_id = resource.department_id
      and (s.status = 'active' or sc.id = resource.semester_course_id)
  ) then
    raise exception 'Choose a subject in this batch' using errcode = '23514';
  end if;

  if target_link_url is not null and trim(target_link_url) <> '' then
    v_url := trim(target_link_url);
    if not (v_url ~* '^https?://') then
      v_url := 'https://' || v_url;
    end if;
  else
    v_url := resource.link_url;
  end if;

  update classmate.file_metadata set
    title = trim(target_title),
    category = v_cat,
    semester_course_id = target_course,
    link_url = v_url
  where id = resource.id returning * into updated;

  update classmate.notices set
    title = left('Resource: ' || updated.title, 200),
    semester_course_id = updated.semester_course_id
  where resource_id = resource.id;

  insert into classmate.audit_log (actor_id, action, target_id)
  values ((select auth.uid()), 'edit_resource_metadata', resource.id);

  return updated;
end;
$$;

revoke all on function classmate.edit_resource_metadata(uuid, text, text, uuid, text) from public, anon;
grant execute on function classmate.edit_resource_metadata(uuid, text, text, uuid, text) to authenticated;

notify pgrst, 'reload schema';

commit;
