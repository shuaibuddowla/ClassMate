begin;
alter table classmate.device_tokens add column app_version integer;
drop function classmate.register_device_token(text);
create function classmate.register_device_token(target_token text,target_version integer default null) returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  if target_token is null or length(target_token)<20 or length(target_token)>4096 then raise exception 'Invalid FCM token'; end if;
  if target_version is not null and target_version<1 then raise exception 'Invalid app version'; end if;
  insert into classmate.device_tokens(profile_id,fcm_token,app_version) values(auth.uid(),target_token,target_version)
  on conflict(fcm_token) do update set profile_id=excluded.profile_id,updated_at=now(),app_version=coalesce(excluded.app_version,classmate.device_tokens.app_version);
end $$;
revoke all on function classmate.register_device_token(text,integer) from public,anon;
grant execute on function classmate.register_device_token(text,integer) to authenticated;
-- Global releases can span many bounded dispatch runs. Accepted device receipts prevent resends.
create or replace function classmate.claim_notification_events(batch_size integer default 20)
returns setof classmate.notification_outbox language sql security definer set search_path='' as $$
  update classmate.notification_outbox o set processing_until=now()+interval '2 minutes',attempts=o.attempts+1
  where o.id in (select id from classmate.notification_outbox
    where delivered_at is null and attempts<case when kind='app_update' then 1000 else 10 end
      and (processing_until is null or processing_until<now())
    order by created_at for update skip locked limit least(greatest(batch_size,1),20)) returning o.*;
$$;
create or replace function classmate.request_notification_dispatch() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; dispatcher_secret text; request_id bigint;
begin
  if not exists(select 1 from classmate.notification_outbox where delivered_at is null and attempts<case when kind='app_update' then 1000 else 10 end and (processing_until is null or processing_until<now()))
     and not exists(select 1 from classmate.resource_cleanup_jobs) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
  select decrypted_secret into dispatcher_secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
  if endpoint is null or dispatcher_secret is null then return null; end if;
  select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',dispatcher_secret),body:='{}'::jsonb,timeout_milliseconds:=10000) into request_id;
  return request_id;
end $$;
notify pgrst,'reload schema';
commit;
