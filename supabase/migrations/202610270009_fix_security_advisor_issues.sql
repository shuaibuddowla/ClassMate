-- ============================================================================
-- Migration: 202610270009_fix_security_advisor_issues.sql
-- Description:
-- 1. Enable Row Level Security (RLS) on classmate.feature_flags and add
--    policies for SELECT (public/authenticated) and WRITE (admins/owners).
-- 2. Set security_invoker = true on classmate.v_course_attendance_sessions
--    and classmate.v_course_student_summary to resolve Security Definer View
--    warnings from the Supabase Advisor.
-- ============================================================================

begin;

-- 1. Resolve 'RLS Disabled in Public' on classmate.feature_flags
alter table classmate.feature_flags enable row level security;

drop policy if exists "feature_flags_select_policy" on classmate.feature_flags;
drop policy if exists "feature_flags_write_policy" on classmate.feature_flags;

create policy "feature_flags_select_policy"
  on classmate.feature_flags
  for select
  to authenticated, anon
  using (true);

create policy "feature_flags_write_policy"
  on classmate.feature_flags
  for all
  to authenticated
  using (
    exists (select 1 from classmate.app_owners where email = auth.jwt()->>'email' and active)
    or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
  )
  with check (
    exists (select 1 from classmate.app_owners where email = auth.jwt()->>'email' and active)
    or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
  );

grant select on classmate.feature_flags to anon, authenticated;

-- 2. Resolve 'Security Definer View' on views
alter view classmate.v_course_attendance_sessions set (security_invoker = true);
alter view classmate.v_course_student_summary set (security_invoker = true);

commit;
notify pgrst, 'reload schema';
