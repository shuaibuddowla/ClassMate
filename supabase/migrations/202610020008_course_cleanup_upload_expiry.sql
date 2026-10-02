begin;
-- Wait out already-issued 120-second PUT URLs before purging objects. A delayed
-- in-flight upload must not recreate an orphan after course deletion.
alter table classmate.resource_cleanup_jobs add column not_before timestamptz not null default now()+interval '3 minutes';
commit;
