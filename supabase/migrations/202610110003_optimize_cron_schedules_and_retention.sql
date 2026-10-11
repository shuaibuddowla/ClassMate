begin;

-- Optimize background cron frequencies and establish automatic log retention
-- to keep ClassMate comfortably within Supabase Free Plan quotas.

do $$
begin
  if exists (select 1 from cron.job where jobname = 'classmate-notification-retry') then
    perform cron.unschedule('classmate-notification-retry');
  end if;
  if exists (select 1 from cron.job where jobname = 'classmate-resource-cleanup') then
    perform cron.unschedule('classmate-resource-cleanup');
  end if;
  if exists (select 1 from cron.job where jobname = 'classmate-purge-cron-logs') then
    perform cron.unschedule('classmate-purge-cron-logs');
  end if;
end $$;

-- 1. Notification retry: Rescheduled from every 1m to every 15m.
-- Immediate deliveries are already handled by the wake_notification_dispatch trigger.
select cron.schedule(
  'classmate-notification-retry',
  '*/15 * * * *',
  'select classmate.request_notification_dispatch();'
);

-- 2. Resource cleanup: Rescheduled from every 1m to once daily at 03:00 UTC.
select cron.schedule(
  'classmate-resource-cleanup',
  '0 3 * * *',
  'select classmate.request_resource_cleanup();'
);

-- 3. Automatic cron log maintenance: Runs daily at 03:30 UTC to prevent cron.job_run_details
-- from accumulating tens of thousands of rows and consuming Postgres storage.
select cron.schedule(
  'classmate-purge-cron-logs',
  '30 3 * * *',
  $$delete from cron.job_run_details where end_time < now() - interval '2 days';$$
);

-- Immediate initial cleanup of accumulated cron history
delete from cron.job_run_details where end_time < now() - interval '2 days';

notify pgrst, 'reload schema';
commit;
