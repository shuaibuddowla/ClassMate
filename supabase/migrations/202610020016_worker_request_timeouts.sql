begin;
-- Keep scheduled HTTP requests open through each worker invocation budget.

create or replace function classmate.request_notification_dispatch() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; dispatcher_secret text; request_id bigint;
begin
  if not exists(select 1 from classmate.notification_outbox where delivered_at is null and not jobs_prepared)
    and not exists(select 1 from classmate.notification_jobs where next_attempt_at<=now() and (state='pending' or (state='leased' and lease_until<now()))) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
  select decrypted_secret into dispatcher_secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
  if endpoint is null or dispatcher_secret is null then return null; end if;
  select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',dispatcher_secret),body:='{}'::jsonb,timeout_milliseconds:=90000) into request_id;
  return request_id;
end $$;

create or replace function classmate.request_resource_cleanup() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; secret text; result bigint;
begin
 if not exists(select 1 from classmate.resource_cleanup_jobs where not_before<=now()) then return null; end if;
 select replace(decrypted_secret,'dispatch-classmate-notifications','cleanup-classmate-resources') into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
 select decrypted_secret into secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
 if endpoint is null or secret is null then return null; end if;
 select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',secret),body:='{}'::jsonb,timeout_milliseconds:=70000) into result;
 return result;
end $$;

create or replace function classmate.finish_notification_job(target_event uuid,target_hash text,target_lease uuid,outcome text,error_code text default null,retry_seconds integer default 60) returns boolean
language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype;
begin
  if outcome not in('accepted','invalid','cancelled','retry','failed','release') then raise exception 'Invalid outcome'; end if;
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' for update;
  if not found then return false; end if;
  if outcome='accepted' then
    insert into classmate.notification_deliveries(outbox_id,profile_id,token_hash) values(j.event_id,j.profile_id,j.token_hash) on conflict do nothing;
  elsif outcome='invalid' then
    delete from classmate.device_tokens where profile_id=j.profile_id and token_hash=j.token_hash;
  end if;
  update classmate.notification_jobs set
    state=case when outcome='release' then 'pending' when outcome='retry' then case when failures+1>=8 then 'failed' else 'pending' end else outcome end,
    failures=failures+case when outcome in('retry','failed') then 1 else 0 end,
    next_attempt_at=now()+make_interval(secs=>case when outcome='retry' then greatest(60,retry_seconds) else 0 end),
    last_error=case when outcome in('retry','failed') then left(error_code,80) else null end,
    lease_id=null,lease_until=null,updated_at=now() where event_id=j.event_id and token_hash=j.token_hash;
  return true;
end $$;

commit;