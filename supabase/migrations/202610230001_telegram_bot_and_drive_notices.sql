begin;

-- 1. Update create_library_link to also generate a notice in classmate.notices
create or replace function classmate.create_library_link(
  target_batch uuid,
  target_course uuid,
  target_title text,
  target_category text,
  target_url text
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

  if target_category is null or target_category not in ('notes', 'slides', 'questions', 'syllabus', 'other') then
    raise exception 'Invalid category' using errcode = '23514';
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
    target_category,
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

-- 2. Create trigger function to automatically broadcast new resources to Telegram for CSE 22
create or replace function classmate.broadcast_resource_to_telegram()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_batch_num int;
  v_secret text;
  v_course_title text := null;
  v_course_code text := null;
  v_uploader_name text := 'ClassMate Contributor';
  v_should_broadcast boolean := false;
begin
  -- Trigger on newly active resources (either inserted as active, e.g. drive links,
  -- or updated from pending to active, e.g. finished file uploads)
  if tg_op = 'INSERT' and new.status = 'active' then
    v_should_broadcast := true;
  elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'active' then
    v_should_broadcast := true;
  end if;

  if not v_should_broadcast then
    return new;
  end if;

  -- Strictly check if target batch is CSE 22
  select batch_number into v_batch_num from classmate.batches where id = new.batch_id;
  if v_batch_num is distinct from 22 then
    return new;
  end if;

  -- Get dispatcher secret from vault
  select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'classmate_dispatch_secret' limit 1;
  if v_secret is null then
    return new;
  end if;

  -- Resolve course title and code if linked to a course
  if new.semester_course_id is not null then
    select sc.course_title, sc.course_code into v_course_title, v_course_code
    from classmate.semester_courses sc
    where sc.id = new.semester_course_id limit 1;
  end if;

  -- Resolve uploader name
  if new.uploaded_by is not null then
    select coalesce(nullif(trim(p.full_name), ''), 'ClassMate Contributor') into v_uploader_name
    from classmate.profiles p
    where p.id = new.uploaded_by limit 1;
  end if;

  -- Asynchronously post to Cloudflare Worker Telegram broadcast endpoint
  perform net.http_post(
    url := 'https://classmate-secure-api.shuaibuddowla-personal.workers.dev/internal/telegram-broadcast',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Dispatch-Secret', v_secret,
      'User-Agent', 'ClassMate-Database/1.0'
    ),
    body := jsonb_build_object(
      'resource_id', new.id,
      'title', new.title,
      'category', new.category,
      'course_title', v_course_title,
      'course_code', v_course_code,
      'uploader_name', v_uploader_name,
      'file_type', new.file_type,
      'batch_number', 22
    )
  );

  return new;
exception when others then
  -- Do not fail the transaction if background Telegram dispatch encounters an issue
  return new;
end;
$$;

drop trigger if exists trg_broadcast_resource_telegram on classmate.file_metadata;
create trigger trg_broadcast_resource_telegram
  after insert or update of status on classmate.file_metadata
  for each row execute function classmate.broadcast_resource_to_telegram();

commit;
