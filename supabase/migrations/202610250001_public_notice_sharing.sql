-- Migration: 202610250001_public_notice_sharing.sql
-- Description: Universal public RPC to retrieve notice details for zero-login sharing and deep linking.

begin;

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

  -- Test if target_identifier is a valid UUID
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
    'resource_title', res.resource_title,
    'resource_file_type', res.resource_file_type,
    'resource_size_bytes', res.resource_size_bytes,
    'resource_mime_type', res.resource_mime_type,
    'resource_link_url', res.resource_link_url,
    'resource_is_link', res.resource_is_link
  );
end;
$$;

-- UUID parameter overload
create or replace function classmate.get_public_notice(target_notice uuid)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select classmate.get_public_notice(target_notice::text);
$$;

-- Public wrappers for default PostgREST RPC access
create or replace function public.get_public_notice(target_identifier text)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select classmate.get_public_notice(target_identifier);
$$;

create or replace function public.get_public_notice(target_notice uuid)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select classmate.get_public_notice(target_notice::text);
$$;

revoke all on function classmate.get_public_notice(text) from public;
revoke all on function classmate.get_public_notice(uuid) from public;
revoke all on function public.get_public_notice(text) from public;
revoke all on function public.get_public_notice(uuid) from public;

grant execute on function classmate.get_public_notice(text) to anon, authenticated, service_role;
grant execute on function classmate.get_public_notice(uuid) to anon, authenticated, service_role;
grant execute on function public.get_public_notice(text) to anon, authenticated, service_role;
grant execute on function public.get_public_notice(uuid) to anon, authenticated, service_role;

notify pgrst, 'reload schema';

commit;
