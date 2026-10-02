begin;
-- Large release broadcasts must not delay a newly posted academic notice.
create or replace function classmate.claim_notification_events(batch_size integer default 20)
returns setof classmate.notification_outbox language sql security definer set search_path='' as $$
  with pending as (
    select id from classmate.notification_outbox
    where delivered_at is null and attempts<case when kind='app_update' then 1000 else 10 end
      and (processing_until is null or processing_until<now())
    order by (kind='app_update'),created_at for update skip locked limit least(greatest(batch_size,1),20)
  ), claimed as (
    update classmate.notification_outbox o set processing_until=now()+interval '2 minutes',attempts=o.attempts+1
    where o.id in(select id from pending) returning o.*
  ) select * from claimed order by (kind='app_update'),created_at;
$$;
commit;
