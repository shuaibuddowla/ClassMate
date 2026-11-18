-- Multi-Image Notice Attachments Support (Up to 5 images per notice)

-- 1. Update prepare_notice_image to support up to 5 images per notice
create or replace function public.prepare_notice_image(
  target_notice uuid,
  image_file_name text,
  image_mime_type text,
  image_size_bytes bigint
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  attachment_id uuid := gen_random_uuid();
  safe_name text;
  object_key text;
  existing_count int;
begin
  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
     or image_size_bytes not between 1 and 10485760 then
    raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 10 MB are supported' using errcode = '22023';
  end if;

  if not exists (
    select 1 from classmate.notices n
    where n.id = target_notice and (n.author_id = auth.uid() or classmate.is_owner())
  ) then
    raise exception 'You cannot attach a file to this notice' using errcode = '42501';
  end if;

  select count(*) into existing_count from public.notice_attachments a where a.notice_id = target_notice;

  if image_mime_type = 'application/pdf' then
    if existing_count > 0 then
      raise exception 'A PDF notice can only have one document attachment' using errcode = '23505';
    end if;
  else
    if exists (select 1 from public.notice_attachments a where a.notice_id = target_notice and a.mime_type = 'application/pdf') then
      raise exception 'Cannot mix images with a PDF attachment' using errcode = '23505';
    end if;
    if existing_count >= 5 then
      raise exception 'Maximum 5 images allowed per notice' using errcode = '23505';
    end if;
  end if;

  safe_name := left(regexp_replace(coalesce(nullif(image_file_name, ''), 'attachment'), '[^A-Za-z0-9._-]', '_', 'g'), 160);
  object_key := target_notice::text || '/' || attachment_id::text || '/' || safe_name;

  insert into public.notice_attachments
    (id, notice_id, uploader_id, storage_key, file_name, mime_type, size_bytes)
  values
    (attachment_id, target_notice, auth.uid(), object_key, safe_name, image_mime_type, image_size_bytes);

  return jsonb_build_object('id', attachment_id, 'storage_key', object_key);
end;
$$;

revoke all on function public.prepare_notice_image(uuid, text, text, bigint) from public, anon;
grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated;

-- 2. Drop and recreate list_visible_notices to return all attachments as JSONB array while preserving legacy columns
drop function if exists public.list_visible_notices(timestamptz, timestamptz, integer);

create function public.list_visible_notices(
  since_time timestamptz default null,
  before_time timestamptz default null,
  page_size integer default 40
)
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
  subject text,
  attachment_storage_key text,
  attachment_file_name text,
  attachment_mime_type text,
  resource_reference text,
  resource_title text,
  resource_subject text,
  resource_provider text,
  attachments jsonb
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    n.id, n.author_id,
    coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1)),
    n.title, n.body, n.priority, n.globally_pinned, n.published_at,
    n.created_at, n.updated_at,
    (select count(*) from public.notice_likes nl where nl.notice_id = n.id),
    exists (select 1 from public.notice_likes nl where nl.notice_id = n.id and nl.profile_id = public.current_profile_id()),
    exists (select 1 from public.notice_pins np where np.notice_id = n.id and np.profile_id = public.current_profile_id()),
    cancellation.subject is not null,
    coalesce(cancellation.subject, n.resource_subject),
    image.storage_key, image.file_name, image.mime_type,
    n.resource_reference, n.resource_title, n.resource_subject, n.resource_provider,
    coalesce(atts.all_attachments, '[]'::jsonb) as attachments
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
  left join lateral (
    select a.storage_key, a.file_name, a.mime_type
    from public.notice_attachments a where a.notice_id = n.id
    order by a.created_at limit 1
  ) image on true
  left join lateral (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'storage_key', a.storage_key,
          'file_name', a.file_name,
          'mime_type', a.mime_type,
          'size_bytes', a.size_bytes
        ) order by a.created_at
      ),
      '[]'::jsonb
    ) as all_attachments
    from public.notice_attachments a
    where a.notice_id = n.id
  ) atts on true
  where public.is_active_user()
    and n.state = 'published' and n.deleted_at is null
    and public.can_read_notice(n.id)
    and (since_time is null or coalesce(n.published_at, n.created_at) >= since_time)
    and (before_time is null or coalesce(n.published_at, n.created_at) < before_time)
  order by n.globally_pinned desc, n.published_at desc nulls last, n.created_at desc
  limit least(greatest(page_size, 1), 100);
$$;

revoke all on function public.list_visible_notices(timestamptz, timestamptz, integer) from public, anon;
grant execute on function public.list_visible_notices(timestamptz, timestamptz, integer) to authenticated;
