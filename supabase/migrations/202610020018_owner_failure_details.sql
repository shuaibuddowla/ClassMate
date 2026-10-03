begin;
-- Only aggregate error codes are exposed; device identities/tokens stay private.
create or replace function classmate.system_health() returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  if not classmate.is_owner() or not classmate.is_active() then raise exception 'Owner required' using errcode='42501'; end if;
  select jsonb_build_object('observed_at',now(),'database_bytes',pg_database_size(current_database()),
    'pending_jobs',(select count(*) from classmate.notification_jobs where state in('pending','leased')),
    'unprepared_events',(select count(*) from classmate.notification_outbox where delivered_at is null and not jobs_prepared),
    'oldest_pending_notice_seconds',(select coalesce(extract(epoch from now()-min(created_at)),0) from classmate.notification_outbox where delivered_at is null and kind<>'app_update'),
    'accepted_sends',(select count(*) from classmate.notification_deliveries),
    'failed_jobs',(select count(*) from classmate.notification_jobs where state='failed'),
    'client_states',(select coalesce(jsonb_object_agg(state,total),'{}') from (select state,count(*) total from classmate.notification_client_receipts group by state) c),
    'largest_tables',(select coalesce(jsonb_agg(t),'[]') from (select relname as name,pg_total_relation_size(relid) as bytes,n_live_tup as estimated_rows from pg_stat_user_tables where schemaname='classmate' order by pg_total_relation_size(relid) desc limit 8) t),
    'failed_events',(select coalesce(jsonb_agg(f),'[]') from (select e.id,e.kind,e.created_at,count(*) as devices,
      (select coalesce(jsonb_agg(reason),'[]') from (select coalesce(j2.last_error,'Unspecified') code,count(*) devices from classmate.notification_jobs j2 where j2.event_id=e.id and j2.state='failed' group by j2.last_error order by count(*) desc limit 5) reason) as reasons
      from classmate.notification_outbox e join classmate.notification_jobs j on j.event_id=e.id and j.state='failed' group by e.id order by e.created_at desc limit 20) f),
    'hosting',(select to_jsonb(h)-'id' from classmate.hosting_usage_snapshots h order by observed_at desc,id desc limit 1)) into result;
  return result;
end $$;
notify pgrst,'reload schema';
commit;
