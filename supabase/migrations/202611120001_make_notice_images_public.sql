-- Make the notice-images bucket public so image thumbnails load without signed URL permission failures
UPDATE storage.buckets
SET public = true
WHERE id = 'notice-images';

-- Allow everyone (anon and authenticated) to read from notice-images
DROP POLICY IF EXISTS "users download visible notice images" ON storage.objects;
CREATE POLICY "users download visible notice images"
ON storage.objects FOR SELECT
TO anon, authenticated
USING (bucket_id = 'notice-images');
