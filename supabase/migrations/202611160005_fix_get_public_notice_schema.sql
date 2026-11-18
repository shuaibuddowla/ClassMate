-- ============================================================================
-- Migration: 202611160005_fix_get_public_notice_schema.sql
-- Description: Fix get_public_notice column references that caused 400 errors and Soft 404s:
--              1. Change f.course_id -> f.semester_course_id
--              2. Change f.uploader_id -> f.uploaded_by
--              3. Change p.avatar_url -> null::text as author_avatar_url (profiles has no avatar_url)
--              4. Change cc.change_date -> cc.effective_date
--              5. Change c.title, c.code -> coalesce(sc.title_override, c.course_title), coalesce(sc.code_override, c.course_code)
-- ============================================================================

begin;

create or replace function classmate.get_public_notice(target_identifier text)
 returns jsonb
 language plpgsql
 security definer
 set search_path to ''
as $function$
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
    null::text as author_avatar_url,
    coalesce(sc.title_override, c.course_title) as course_title,
    coalesce(sc.code_override, c.course_code) as course_code,
    (cc.id is not null) as is_cancelled,
    cc.kind as class_change_kind,
    cc.effective_date as class_change_date,
    cc.details as class_change_details,
    fm.id as resource_id,
    fm.slug as resource_slug,
    fm.title as resource_title,
    fm.file_type as resource_file_type,
    fm.size_bytes as resource_size_bytes,
    fm.mime_type as resource_mime_type,
    fm.link_url as resource_link_url,
    (fm.link_url is not null and fm.link_url <> '') as resource_is_link
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
    where f.batch_id = n.batch_id 
      and (f.semester_course_id = sc.id or f.uploaded_by = n.author_id)
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
    'resource_slug', res.resource_slug,
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
$function$;

grant execute on function classmate.get_public_notice(text) to anon, authenticated, service_role;

commit;
