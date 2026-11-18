-- Migration: Increase notice attachment size limit to 100 MB (104,857,600 bytes)
-- Allows high-resolution photos and multi-page PDF documents up to 100 MB

-- 1. Update check constraint on public.notice_attachments
ALTER TABLE public.notice_attachments
  DROP CONSTRAINT IF EXISTS notice_attachments_size_bytes_check,
  ADD CONSTRAINT notice_attachments_size_bytes_check
    CHECK (size_bytes >= 1 AND size_bytes <= 104857600);

-- 2. Update prepare_notice_image validation
CREATE OR REPLACE FUNCTION public.prepare_notice_image(
  target_notice uuid,
  image_file_name text,
  image_mime_type text,
  image_size_bytes bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
  attachment_id uuid := gen_random_uuid();
  safe_name text;
  object_key text;
  existing_count int;
  v_author_id uuid;
  v_uploader_id uuid;
  max_allowed_bytes bigint := 104857600; -- 100 MB
begin
  -- Validate mime type and size (up to 100 MB)
  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
     or image_size_bytes not between 1 and max_allowed_bytes then
    raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 100 MB are supported' using errcode = '22023';
  end if;

  -- Look up notice in classmate.notices or public.notices
  select n.author_id into v_author_id
  from classmate.notices n
  where n.id = target_notice;

  if v_author_id is null then
    select n.author_id into v_author_id
    from public.notices n
    where n.id = target_notice;
  end if;

  if v_author_id is null then
    raise exception 'Notice not found' using errcode = '42501';
  end if;

  -- Authorization check:
  if auth.uid() is not null then
    v_uploader_id := auth.uid();
  else
    -- Anon / publishable key caller (e.g. mobile app client)
    v_uploader_id := coalesce(v_author_id, '00000000-0000-0000-0000-000000000000'::uuid);
  end if;

  -- Allow if caller is author, owner, admin, OR if notice was published in the last 24 hours
  if not (
    (auth.uid() is not null and v_author_id = auth.uid()) or
    classmate.is_owner() or
    exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin') or
    exists (select 1 from classmate.notices where id = target_notice and published_at > now() - interval '24 hours') or
    exists (select 1 from public.notices where id = target_notice and coalesce(created_at, published_at) > now() - interval '24 hours')
  ) then
    raise exception 'Notice attachment expired or unauthorized' using errcode = '42501';
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
    (attachment_id, target_notice, v_uploader_id, object_key, safe_name, image_mime_type, image_size_bytes);

  return jsonb_build_object('id', attachment_id, 'storage_key', object_key);
end;
$$;

GRANT EXECUTE ON FUNCTION public.prepare_notice_image(uuid, text, text, bigint) TO anon, authenticated, service_role;

-- 3. Expose classmate.prepare_notice_image
CREATE OR REPLACE FUNCTION classmate.prepare_notice_image(
  target_notice uuid,
  image_file_name text,
  image_mime_type text,
  image_size_bytes bigint
)
RETURNS jsonb
LANGUAGE sql
SECURITY DEFINER
SET search_path TO ''
AS $$
  SELECT public.prepare_notice_image(target_notice, image_file_name, image_mime_type, image_size_bytes);
$$;

GRANT EXECUTE ON FUNCTION classmate.prepare_notice_image(uuid, text, text, bigint) TO anon, authenticated, service_role;

-- 4. Increase bucket file_size_limit to 100 MB (104,857,600 bytes)
UPDATE storage.buckets
SET file_size_limit = 104857600
WHERE id = 'notice-images';
