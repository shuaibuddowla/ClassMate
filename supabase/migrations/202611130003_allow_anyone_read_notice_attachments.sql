DROP POLICY IF EXISTS "anyone can read notice attachments" ON public.notice_attachments;
CREATE POLICY "anyone can read notice attachments"
ON public.notice_attachments
FOR SELECT
TO anon, authenticated
USING (true);

NOTIFY pgrst, 'reload schema';
