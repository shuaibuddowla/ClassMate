-- Migration: 202611250006_pending_notice_media_cleanup_rpc.sql
-- Description: Provide a service_role-only helper RPC for cleanup-classmate-resources to find
--   orphaned staged files in notice-images/pending/ older than 24 hours.

BEGIN;

CREATE OR REPLACE FUNCTION classmate.list_orphaned_pending_notice_media()
RETURNS text[]
LANGUAGE sql
SECURITY DEFINER
SET search_path TO ''
AS $$
  SELECT coalesce(array_agg(o.name ORDER BY o.created_at ASC), array[]::text[])
  FROM (
    SELECT o.name, o.created_at
    FROM storage.objects o
    WHERE o.bucket_id = 'notice-images'
      AND o.name LIKE 'pending/%'
      AND o.created_at < now() - interval '24 hours'
      AND NOT EXISTS (
        SELECT 1 FROM public.notice_attachments a WHERE a.storage_key = o.name
      )
    ORDER BY o.created_at ASC
    LIMIT 50
  ) o;
$$;

REVOKE EXECUTE ON FUNCTION classmate.list_orphaned_pending_notice_media() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION classmate.list_orphaned_pending_notice_media() TO service_role;

COMMIT;
