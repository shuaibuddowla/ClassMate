-- Allow anon (fallback for clients during token migration) to prepare and upload notice attachments for recently created notices
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
AS $function$
declare
  attachment_id uuid := gen_random_uuid();
  safe_name text;
  object_key text;
  existing_count int;
  p_role text;
  v_author_id uuid;
  v_uploader_id uuid;
begin
  -- Validate mime type and size
  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
     or image_size_bytes not between 1 and 10485760 then
    raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 10 MB are supported' using errcode = '22023';
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

  -- Check authorization
  if auth.uid() is not null then
    select role into p_role from classmate.profiles where id = auth.uid();
    if not (v_author_id = auth.uid() or classmate.is_owner() or p_role = 'admin') then
      raise exception 'You cannot attach a file to this notice' using errcode = '42501';
    end if;
    v_uploader_id := auth.uid();
  else
    -- Unauthenticated / anon call (e.g. from existing client token flow):
    -- Allow only if the notice was created in the last 30 minutes
    if not exists (
      select 1 from classmate.notices
      where id = target_notice and published_at > now() - interval '30 minutes'
    ) and not exists (
      select 1 from public.notices
      where id = target_notice and coalesce(created_at, published_at) > now() - interval '30 minutes'
    ) then
      raise exception 'Notice attachment expired or unauthorized' using errcode = '42501';
    end if;
    v_uploader_id := v_author_id;
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
$function$;

GRANT EXECUTE ON FUNCTION public.prepare_notice_image(uuid, text, text, bigint) TO anon, authenticated, service_role;

-- 2. Update storage policy on notice-images to allow upload if matching recent attachment
DROP POLICY IF EXISTS "publishers upload their notice images" ON storage.objects;
DROP POLICY IF EXISTS "Publishers upload notice images via attachment record" ON storage.objects;

CREATE POLICY "publishers upload their notice images"
ON storage.objects FOR INSERT
TO anon, authenticated
WITH CHECK (
  bucket_id = 'notice-images'
  AND (
    EXISTS (
      SELECT 1 FROM public.notice_attachments a
      WHERE a.storage_key = storage.objects.name
        AND a.created_at > now() - interval '30 minutes'
    )
    OR (auth.uid() IS NOT NULL AND (
      EXISTS (
        SELECT 1 FROM classmate.profiles
        WHERE profiles.id = auth.uid() AND profiles.role = 'admin'::classmate.user_role
      )
      OR classmate.is_owner()
    ))
  )
);
