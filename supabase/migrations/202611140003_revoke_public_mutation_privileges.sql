-- ============================================================================
-- Migration: 202611140003_revoke_public_mutation_privileges.sql
-- Fixes:
-- 1. Revoke unauthenticated (anon/public) execution on all mutation RPCs:
--    - classmate.process_rfid_scan
--    - classmate.discard_failed_notice_upload
--    - classmate.prepare_notice_image
-- 2. Revoke table-level write privileges (INSERT, UPDATE, DELETE) on all
--    public schema tables from anon to ensure nobody can write data without authentication.
-- 3. Revoke unauthenticated execution on legacy public mutation functions.
-- ============================================================================

begin;

-- 1. Secure classmate schema mutation RPCs
revoke all on function classmate.process_rfid_scan(text, text) from public;
revoke all on function classmate.process_rfid_scan(text, text) from anon;
grant execute on function classmate.process_rfid_scan(text, text) to authenticated, service_role;

revoke all on function classmate.discard_failed_notice_upload(uuid, text) from public;
revoke all on function classmate.discard_failed_notice_upload(uuid, text) from anon;
grant execute on function classmate.discard_failed_notice_upload(uuid, text) to authenticated, service_role;

revoke all on function classmate.prepare_notice_image(uuid, text, text, bigint) from public;
revoke all on function classmate.prepare_notice_image(uuid, text, text, bigint) from anon;
grant execute on function classmate.prepare_notice_image(uuid, text, text, bigint) to authenticated, service_role;

-- 2. Revoke all write permissions (INSERT, UPDATE, DELETE) on public tables from anon
do $$
declare
  r record;
begin
  for r in (select tablename from pg_tables where schemaname = 'public') loop
    execute format('revoke insert, update, delete on table public.%I from anon, public;', r.tablename);
  end loop;
end $$;

-- Ensure default privileges do not grant write permissions on future public tables to anon
alter default privileges in schema public revoke insert, update, delete on tables from anon;

-- 3. Revoke unauthenticated execution on legacy public mutation functions
do $$
begin
  if exists (select 1 from pg_proc where proname = 'publish_batch_notice' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.publish_batch_notice(text, text, text, text) from public, anon;';
    execute 'grant execute on function public.publish_batch_notice(text, text, text, text) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'delete_notice' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.delete_notice(uuid) from public, anon;';
    execute 'grant execute on function public.delete_notice(uuid) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'discard_failed_notice_upload' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.discard_failed_notice_upload(uuid, text) from public, anon;';
    execute 'grant execute on function public.discard_failed_notice_upload(uuid, text) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'prepare_notice_image' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.prepare_notice_image(uuid, text, text, bigint) from public, anon;';
    execute 'grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'archive_academic_resource' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.archive_academic_resource(uuid) from public, anon;';
    execute 'grant execute on function public.archive_academic_resource(uuid) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'set_notice_global_pin' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.set_notice_global_pin(uuid, boolean) from public, anon;';
    execute 'grant execute on function public.set_notice_global_pin(uuid, boolean) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'set_notice_personal_pin' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.set_notice_personal_pin(uuid, boolean) from public, anon;';
    execute 'grant execute on function public.set_notice_personal_pin(uuid, boolean) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'set_notice_like' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.set_notice_like(uuid, boolean) from public, anon;';
    execute 'grant execute on function public.set_notice_like(uuid, boolean) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'set_notice_reminder' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.set_notice_reminder(uuid, timestamptz) from public, anon;';
    execute 'grant execute on function public.set_notice_reminder(uuid, timestamptz) to authenticated, service_role;';
  end if;

  if exists (select 1 from pg_proc where proname = 'remove_notice_reminder' and pronamespace = 'public'::regnamespace) then
    execute 'revoke all on function public.remove_notice_reminder(uuid) from public, anon;';
    execute 'grant execute on function public.remove_notice_reminder(uuid) to authenticated, service_role;';
  end if;
end $$;

notify pgrst, 'reload schema';
commit;
