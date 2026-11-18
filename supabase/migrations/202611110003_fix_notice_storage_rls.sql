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
        AND a.created_at > now() - interval '2 hours'
    )
  )
);
