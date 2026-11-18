DROP POLICY IF EXISTS "anyone can verify recent attachments for upload" ON public.notice_attachments;
CREATE POLICY "anyone can verify recent attachments for upload"
ON public.notice_attachments FOR SELECT
TO anon, authenticated
USING (created_at > now() - interval '24 hours');

DROP POLICY IF EXISTS "publishers upload their notice images" ON storage.objects;
CREATE POLICY "publishers upload their notice images"
ON storage.objects FOR INSERT
TO anon, authenticated
WITH CHECK (
  bucket_id = 'notice-images'
  AND (
    EXISTS (
      SELECT 1 FROM public.notice_attachments a
      WHERE a.storage_key = storage.objects.name
        AND a.created_at > now() - interval '24 hours'
    )
  )
);
