-- Migration: 202611130004_disable_telegram_broadcast_and_link_resource_notices.sql
-- Description: Shut down automatic Telegram channel resource forwarding and embed official /r/[slug] resource links into notice feed and sharing.

begin;

-- 1. Disable and drop Telegram broadcast trigger on file_metadata
drop trigger if exists trg_broadcast_resource_telegram on classmate.file_metadata;

create or replace function classmate.trigger_broadcast_resource_telegram()
returns trigger
language plpgsql
security definer
as $$
begin
  -- Telegram forwarding permanently shut down per user preference
  return new;
end;
$$;

create or replace function classmate.broadcast_resource_to_telegram()
returns trigger
language plpgsql
security definer
as $$
begin
  -- Telegram forwarding permanently shut down per user preference
  return new;
end;
$$;

-- 2. Update sync_resource_notice() to generate notice with exact https://classmatebd.vercel.app/r/[slug] link
create or replace function classmate.sync_resource_notice()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  resource_title text;
  v_slug text;
  v_url text;
begin
  if old.status = 'pending' and new.status = 'active' then
    resource_title := left('Resource: ' || new.title, 200);
    v_slug := coalesce(nullif(trim(new.slug), ''), new.id::text);
    v_url := 'https://classmatebd.vercel.app/r/' || v_slug;
    insert into classmate.notices(department_id, batch_id, semester_course_id,
      author_id, title, body, resource_id)
    values (new.department_id, new.batch_id, new.semester_course_id,
      new.uploaded_by, resource_title,
      'A new learning resource has been shared: ' || v_url, new.id);
  elsif old.status in ('active', 'deleting') and new.status = 'archived' then
    delete from classmate.notification_outbox o
      using classmate.notices n
      where n.resource_id = new.id and o.kind = 'notice' and o.record_id = n.id;
    delete from classmate.notices where resource_id = new.id;
  end if;
  return new;
end;
$$;

-- 3. Backfill existing resource notices with the /r/[slug] link
update classmate.notices n
set body = 'A new learning resource has been shared: https://classmatebd.vercel.app/r/' || coalesce(nullif(trim(f.slug), ''), f.id::text)
from classmate.file_metadata f
where n.resource_id = f.id
  and (n.body = 'A new learning resource has been shared. Open the file below.' or n.body like 'A new learning resource has been shared%');

-- 4. Update classmate.get_public_notice to include resource_slug
create or replace function classmate.get_public_notice(target_identifier text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  res record;
  as_uuid uuid := null;
begin
  if target_identifier is null or trim(target_identifier) = '' then
    return null;
  end if;

  if target_identifier ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    as_uuid := target_identifier::uuid;
  else
    return null;
  end if;

  select
    n.id,
    n.title,
    n.body,
    n.published_at,
    n.department_id,
    d.name as department_name,
    d.code as department_code,
    n.batch_id,
    b.batch_number,
    b.academic_session,
    n.author_id,
    coalesce(nullif(trim(p.full_name), ''), 'ClassMate Member') as author_name,
    p.role as author_role,
    p.is_cr as author_is_cr,
    case
      when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture')
      else null
    end as author_avatar_url,
    coalesce(sc.title_override, c.course_title) as course_title,
    coalesce(sc.code_override, c.course_code) as course_code,
    (cc.id is not null and cc.kind = 'cancelled') as is_cancelled,
    cc.kind as class_change_kind,
    cc.effective_date as class_change_date,
    cc.details as class_change_details,
    f.id as resource_id,
    f.slug as resource_slug,
    f.title as resource_title,
    f.file_type as resource_file_type,
    f.size_bytes as resource_size_bytes,
    f.mime_type as resource_mime_type,
    f.link_url as resource_link_url,
    (f.link_url is not null and f.link_url <> '') as resource_is_link
  into res
  from classmate.notices n
  join classmate.departments d on d.id = n.department_id
  join classmate.batches b on b.id = n.batch_id
  left join classmate.profiles p on p.id = n.author_id
  left join auth.users u on u.id = n.author_id
  left join classmate.semester_courses sc on sc.id = n.semester_course_id
  left join classmate.courses c on c.id = sc.course_id
  left join classmate.class_changes cc on cc.id = n.class_change_id
  left join classmate.file_metadata f on f.id = n.resource_id and f.status = 'active'
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
    'resource_slug', res.resource_slug,
    'resource_title', res.resource_title,
    'resource_file_type', res.resource_file_type,
    'resource_size_bytes', res.resource_size_bytes,
    'resource_mime_type', res.resource_mime_type,
    'resource_link_url', res.resource_link_url,
    'resource_is_link', res.resource_is_link
  );
end;
$$;

grant execute on function classmate.get_public_notice(text) to anon, authenticated, service_role;
grant execute on function public.get_public_notice(text) to anon, authenticated, service_role;

notify pgrst, 'reload schema';

commit;
