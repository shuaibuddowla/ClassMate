-- Migration: 202611250002_atomic_notice_posting_and_staging.sql
-- Description:
--   1. Add client_request_id column + partial unique index on classmate.notices for idempotent publishing.
--   2. Add user-scoped staging RLS policy on storage.objects (notice-images/pending/<uid>/...) so media
--      uploads BEFORE the notice is created.
--   3. Upgrade classmate.post_notice to accept optional target_attachments jsonb and client_request_id uuid,
--      verifying all uploaded objects exist in storage.objects and committing notice + attachments +
--      notification_outbox atomically in a single transaction (backwards-compatible with 4-arg callers).

BEGIN;

-- 1. Idempotency key on classmate.notices
ALTER TABLE classmate.notices
  ADD COLUMN IF NOT EXISTS client_request_id uuid;

CREATE UNIQUE INDEX IF NOT EXISTS notices_author_client_request_id_uq
  ON classmate.notices (author_id, client_request_id)
  WHERE client_request_id IS NOT NULL;

-- 2. User-scoped staging policy on storage.objects for upload-first notice attachments
DROP POLICY IF EXISTS "authenticated manage own staged notice media" ON storage.objects;
CREATE POLICY "authenticated manage own staged notice media"
ON storage.objects
FOR ALL
TO authenticated
USING (
  bucket_id = 'notice-images'
  AND (storage.foldername(name))[1] = 'pending'
  AND (storage.foldername(name))[2] = auth.uid()::text
)
WITH CHECK (
  bucket_id = 'notice-images'
  AND (storage.foldername(name))[1] = 'pending'
  AND (storage.foldername(name))[2] = auth.uid()::text
);

-- 3. Upgrade classmate.post_notice to atomic + idempotent publish
DROP FUNCTION IF EXISTS classmate.post_notice(uuid, uuid, text, text);

CREATE OR REPLACE FUNCTION classmate.post_notice(
  target_batch uuid,
  target_course uuid,
  notice_title text,
  notice_body text,
  target_attachments jsonb DEFAULT '[]'::jsonb,
  client_request_id uuid DEFAULT NULL
)
RETURNS classmate.notices
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
  n classmate.notices%rowtype;
  d uuid;
  v_uid uuid := auth.uid();
  v_attachments jsonb := coalesce(target_attachments, '[]'::jsonb);
  v_att jsonb;
  v_att_count int;
  v_has_pdf boolean := false;
  v_storage_key text;
  v_mime_type text;
  v_size_bytes bigint;
  v_max_bytes bigint := 104857600; -- 100 MB
begin
  if v_uid is null or not classmate.can_post(target_batch, target_course) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;

  -- Idempotent retry check: return existing notice if this client_request_id already committed
  if client_request_id is not null then
    select * into n
    from classmate.notices existing
    where existing.author_id = v_uid
      and existing.client_request_id = post_notice.client_request_id
    limit 1;
    if found then
      return n;
    end if;
  end if;

  if target_course is not null and not exists (
    select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_course and s.batch_id = target_batch and s.status = 'active'
  ) then
    raise exception 'Course semester is not active';
  end if;

  if jsonb_typeof(v_attachments) <> 'array' then
    raise exception 'Invalid attachments payload' using errcode = '22023';
  end if;

  v_att_count := jsonb_array_length(v_attachments);
  if v_att_count > 5 then
    raise exception 'Maximum 5 attachments allowed per notice' using errcode = '23505';
  end if;

  -- Verify every staged attachment exists in storage BEFORE inserting the notice
  for v_att in select * from jsonb_array_elements(v_attachments) loop
    v_storage_key := trim(coalesce(v_att->>'storage_key', ''));
    v_mime_type := lower(trim(coalesce(v_att->>'mime_type', '')));
    v_size_bytes := coalesce(nullif(v_att->>'size_bytes', '')::bigint, 0);

    if v_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
       or v_size_bytes not between 1 and v_max_bytes then
      raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 100 MB are supported' using errcode = '22023';
    end if;

    if v_mime_type = 'application/pdf' then
      if v_att_count > 1 then
        raise exception 'A PDF notice can only have one document attachment' using errcode = '23505';
      end if;
      v_has_pdf := true;
    elsif v_has_pdf then
      raise exception 'Cannot mix images with a PDF attachment' using errcode = '23505';
    end if;

    if v_storage_key = '' or not (v_storage_key like ('pending/' || v_uid::text || '/%')) then
      raise exception 'Unauthorized attachment storage key' using errcode = '42501';
    end if;

    if not exists (
      select 1 from storage.objects o
      where o.bucket_id = 'notice-images' and o.name = v_storage_key
    ) then
      raise exception 'Attachment file has not finished uploading' using errcode = '22023';
    end if;
  end loop;

  select department_id into d from classmate.batches where id = target_batch;

  insert into classmate.notices(
    department_id, batch_id, semester_course_id,
    author_id, title, body, client_request_id
  )
  values (
    d, target_batch, target_course,
    v_uid, trim(notice_title), coalesce(notice_body, ''), client_request_id
  )
  returning * into n;

  if v_att_count > 0 then
    insert into public.notice_attachments (
      id, notice_id, uploader_id, storage_key, file_name, mime_type, size_bytes
    )
    select
      gen_random_uuid(),
      n.id,
      v_uid,
      trim(att->>'storage_key'),
      left(regexp_replace(coalesce(nullif(trim(att->>'file_name'), ''), 'attachment'), '[^A-Za-z0-9._-]', '_', 'g'), 160),
      lower(trim(att->>'mime_type')),
      (att->>'size_bytes')::bigint
    from jsonb_array_elements(v_attachments) as att;
  end if;

  return n;
end;
$function$;

REVOKE EXECUTE ON FUNCTION classmate.post_notice(uuid, uuid, text, text, jsonb, uuid) FROM anon, public;
GRANT EXECUTE ON FUNCTION classmate.post_notice(uuid, uuid, text, text, jsonb, uuid) TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';

COMMIT;
