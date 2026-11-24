-- ============================================================================
-- Migration: 202611240002_unschedule_assessment_reminders_cron.sql
-- Description:
--   Completely disables server-side pg_cron polling and triggers for assessment
--   reminders to eliminate Supabase compute consumption. Reminders are handled
--   100% on-device by Android AlarmManager/WorkManager (zero cloud compute).
-- ============================================================================

begin;

-- 1. Unschedule pg_cron job so it never wakes the database
do $$
begin
  if exists (select 1 from cron.job where jobname = 'classmate-assessment-reminders') then
    perform cron.unschedule('classmate-assessment-reminders');
  end if;
end $$;

-- 2. Drop automatic reminder table sync trigger on assessments
drop trigger if exists trigger_sync_assessment_reminders on classmate.assessments;

-- 3. Replace process_due_assessment_reminders with a no-op that returns 0
create or replace function classmate.process_due_assessment_reminders()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- 100% on-device AlarmManager handles assessment reminders with zero cloud compute
  return 0;
end;
$$;

-- 4. Clean up any stale records from assessment_reminders table
truncate table classmate.assessment_reminders;

commit;
