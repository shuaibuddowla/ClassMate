-- Create classmate.notice_attachments view pointing to public.notice_attachments
CREATE OR REPLACE VIEW classmate.notice_attachments AS 
SELECT * FROM public.notice_attachments;

GRANT SELECT ON classmate.notice_attachments TO authenticated, anon;

NOTIFY pgrst, 'reload schema';
