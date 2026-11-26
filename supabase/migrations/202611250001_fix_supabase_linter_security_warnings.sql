-- Migration: 202611250001_fix_supabase_linter_security_warnings.sql
-- Description: Fix Supabase database linter errors and security warnings:
--   1. Fix security_definer_view: Set security_invoker = true on views.
--   2. Fix rls_disabled_in_public: Enable RLS and add policies on classmate.preseeded_student_roster.
--   3. Fix function_search_path_mutable: Set explicit search_path on 11 functions.
--   4. Fix public_bucket_allows_listing: Drop broad SELECT policies on public storage buckets.
--   5. Fix anon_security_definer_function_executable: Revoke anon execute on sensitive RPCs and internal helpers.
--   6. Fix authenticated_security_definer_function_executable for internal background/queue workers: Restrict to service_role.

-- ============================================================================
-- 1. FIX VIEWS (security_definer_view ERROR)
-- ============================================================================
ALTER VIEW classmate.notice_attachments SET (security_invoker = true);
ALTER VIEW classmate.v_course_attendance_sessions SET (security_invoker = true);

-- ============================================================================
-- 2. FIX TABLE RLS (rls_disabled_in_public ERROR)
-- ============================================================================
ALTER TABLE classmate.preseeded_student_roster ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON classmate.preseeded_student_roster FROM anon, public;
GRANT SELECT ON classmate.preseeded_student_roster TO authenticated, service_role;

DROP POLICY IF EXISTS preseeded_roster_service_role ON classmate.preseeded_student_roster;
CREATE POLICY preseeded_roster_service_role ON classmate.preseeded_student_roster
  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS preseeded_roster_authenticated_read ON classmate.preseeded_student_roster;
CREATE POLICY preseeded_roster_authenticated_read ON classmate.preseeded_student_roster
  FOR SELECT TO authenticated
  USING (
    email = auth.jwt()->>'email' 
    OR student_id = auth.jwt()->'user_metadata'->>'student_id'
    OR classmate.is_owner()
  );

-- ============================================================================
-- 3. FIX MUTABLE SEARCH PATH (function_search_path_mutable WARN)
-- ============================================================================
ALTER FUNCTION classmate.canonical_fund_title(text) SET search_path = classmate, public, pg_temp;
ALTER FUNCTION public.get_r2_storage_stats() SET search_path = public, classmate, pg_temp;
ALTER FUNCTION public.get_public_resource(text) SET search_path = public, classmate, pg_temp;
ALTER FUNCTION public.get_public_resource(uuid) SET search_path = public, classmate, pg_temp;
ALTER FUNCTION classmate.get_public_resource(text) SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.get_public_resource(uuid) SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.set_resource_slug_trigger() SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.generate_unique_resource_slug(text, uuid) SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.trigger_broadcast_resource_telegram() SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.broadcast_resource_to_telegram() SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.slugify(text) SET search_path = classmate, public, pg_temp;
ALTER FUNCTION classmate.trg_enforce_session_course_and_batch() SET search_path = classmate, public, pg_temp;

-- ============================================================================
-- 4. FIX STORAGE LISTING POLICIES (public_bucket_allows_listing WARN)
-- ============================================================================
DROP POLICY IF EXISTS "Public apk-builds access" ON storage.objects;
DROP POLICY IF EXISTS "Public avatar access" ON storage.objects;
DROP POLICY IF EXISTS "users download visible notice images" ON storage.objects;

-- ============================================================================
-- 5. FIX ANON SECURITY DEFINER EXECUTABLES (anon_security_definer_function_executable WARN)
-- ============================================================================
REVOKE EXECUTE ON FUNCTION classmate.auto_assign_first_batch_cr() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.batch_friends(uuid,text,integer) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.broadcast_resource_to_telegram() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.can_manage_attendance_session(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.can_manage_batch(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.can_post(uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.can_read_batch(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.can_read_notice(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.enroll_student_rfid(uuid,text,uuid,text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_attendance_feature_status(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_device_active_session(text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_device_session_roster(text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_my_institution() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_poll_voters(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_r2_storage_stats() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_session_summary(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_student_course_attendance_overview(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.get_unregistered_rfid_scans() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.is_attendance_feature_enabled(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.is_owner() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.is_readd_student(text,uuid,text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.link_attendance_device(text,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.lookup_student_by_card(text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.notice_feed_details(uuid[]) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.owner_batch_overrides() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.process_due_assessment_reminders() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.sync_assessment_reminders() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.sync_resource_notice() FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.toggle_attendance_feature(boolean) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.toggle_student_attendance(uuid,uuid,boolean) FROM anon, public;
REVOKE EXECUTE ON FUNCTION classmate.trigger_broadcast_resource_telegram() FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_access_batch(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_access_department(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_access_offering(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_access_section(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_admin_academic_scope(uuid,uuid,uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_manage_academic_scope(uuid,uuid,uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_manage_notice(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_manage_notice_target(uuid,uuid,uuid,uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_post(uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_read_notice(uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION can_send_v2_batch_notification(text) FROM anon, public;
REVOKE EXECUTE ON FUNCTION current_profile_id() FROM anon, public;
REVOKE EXECUTE ON FUNCTION get_r2_storage_stats() FROM anon, public;
REVOKE EXECUTE ON FUNCTION has_active_role(app_role,uuid,uuid,uuid,uuid) FROM anon, public;
REVOKE EXECUTE ON FUNCTION has_publisher_role() FROM anon, public;
REVOKE EXECUTE ON FUNCTION is_active_user() FROM anon, public;
REVOKE EXECUTE ON FUNCTION notice_feed_details(uuid[]) FROM anon, public;
REVOKE EXECUTE ON FUNCTION rls_auto_enable() FROM anon, public;

-- Ensure public endpoints remain accessible to anon & authenticated
GRANT EXECUTE ON FUNCTION classmate.get_public_notice(uuid) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION classmate.get_public_notice(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION classmate.get_public_resource(uuid) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION classmate.get_public_resource(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_notice(uuid) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_notice(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_resource(uuid) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_resource(text) TO anon, authenticated;

-- Prevent future functions in exposed schemas from being automatically granted to public
ALTER DEFAULT PRIVILEGES IN SCHEMA classmate REVOKE EXECUTE ON FUNCTIONS FROM public;

-- ============================================================================
-- 6. RESTRICT BACKGROUND WORKER & INTERNAL RPCS TO SERVICE_ROLE
-- ============================================================================
REVOKE EXECUTE ON FUNCTION classmate.claim_notification_events(integer) FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.claim_notification_events(integer) TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.claim_notification_jobs(integer) FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.claim_notification_jobs(integer) TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.complete_notification_events() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.complete_notification_events() TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.finish_notification_job(uuid,text,uuid,text,text,integer) FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.finish_notification_job(uuid,text,uuid,text,text,integer) TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.prepare_notification_jobs(integer) FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.prepare_notification_jobs(integer) TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.process_due_assessment_reminders() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.process_due_assessment_reminders() TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.broadcast_resource_to_telegram() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.broadcast_resource_to_telegram() TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.trigger_broadcast_resource_telegram() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.trigger_broadcast_resource_telegram() TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION classmate.sync_resource_notice() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION classmate.sync_resource_notice() TO service_role, postgres;
REVOKE EXECUTE ON FUNCTION rls_auto_enable() FROM authenticated, anon, public;
GRANT EXECUTE ON FUNCTION rls_auto_enable() TO service_role, postgres;
