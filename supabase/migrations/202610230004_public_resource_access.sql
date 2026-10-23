begin;

create or replace function classmate.get_public_resource(target_resource uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  res record;
begin
  select
    f.id,
    f.title,
    f.file_type,
    f.size_bytes,
    f.mime_type,
    f.link_url,
    f.created_at,
    (f.link_url is not null and f.link_url <> '') as is_link,
    coalesce(sc.title_override, c.course_title) as course_title,
    coalesce(sc.code_override, c.course_code) as course_code,
    b.batch_number
  into res
  from classmate.file_metadata f
  left join classmate.semester_courses sc on sc.id = f.semester_course_id
  left join classmate.courses c on c.id = sc.course_id
  left join classmate.batches b on b.id = f.batch_id
  where f.id = target_resource and f.status = 'active';

  if res.id is null then
    return null;
  end if;

  return jsonb_build_object(
    'id', res.id,
    'title', res.title,
    'file_type', res.file_type,
    'size_bytes', res.size_bytes,
    'mime_type', res.mime_type,
    'link_url', res.link_url,
    'is_link', res.is_link,
    'course_title', res.course_title,
    'course_code', res.course_code,
    'batch_number', res.batch_number,
    'created_at', res.created_at
  );
end;
$$;

revoke all on function classmate.get_public_resource(uuid) from public;
grant execute on function classmate.get_public_resource(uuid) to anon, authenticated, service_role;

-- Also provide public.get_public_resource wrapper for standard PostgREST RPC access
create or replace function public.get_public_resource(target_resource uuid)
returns jsonb
language sql
security definer
as $$
  select classmate.get_public_resource(target_resource);
$$;

revoke all on function public.get_public_resource(uuid) from public;
grant execute on function public.get_public_resource(uuid) to anon, authenticated, service_role;

notify pgrst, 'reload schema';

commit;
