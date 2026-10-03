begin;
create or replace function classmate.notification_job_context(target_event uuid,target_hash text,target_lease uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype; e classmate.notification_outbox%rowtype; content jsonb;
begin
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' and lease_until>now();
  if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
  select * into e from classmate.notification_outbox where id=target_event;
  case e.kind
    when 'notice' then select to_jsonb(n) into content from classmate.notices n where id=e.record_id and not silent;
    when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id=e.record_id and status='active';
    when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id=e.record_id;
    when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id=e.record_id;
    when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id=e.record_id;
    else return null;
  end case;
  if content is null or (e.kind<>'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind<>'semester' and content->>'department_id' is distinct from e.department_id::text))) then return null; end if;
  return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
    'batch_id',coalesce(e.batch_id,(select batch_id from classmate.profiles where id=j.profile_id)),
    'title',left(coalesce(content->>'title','ClassMate update'),200),'body',left(coalesce(content->>'body','New class update available'),240),
    'failures',j.failures,'version_code',content->>'version_code','version_name',content->>'version_name');
end $$;


create function classmate.request_resource_cleanup() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; secret text; result bigint;
begin
 if not exists(select 1 from classmate.resource_cleanup_jobs where not_before<=now()) then return null; end if;
 select replace(decrypted_secret,'dispatch-classmate-notifications','cleanup-classmate-resources') into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
 select decrypted_secret into secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
 if endpoint is null or secret is null then return null; end if;
 select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',secret),body:='{}'::jsonb,timeout_milliseconds:=10000) into result;
 return result;
end $$;
revoke all on function classmate.request_resource_cleanup() from public,anon,authenticated;
grant execute on function classmate.request_resource_cleanup() to service_role;
select cron.schedule('classmate-resource-cleanup','* * * * *','select classmate.request_resource_cleanup();');
notify pgrst,'reload schema';
commit;
