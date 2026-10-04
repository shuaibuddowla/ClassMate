begin;
alter table classmate.device_tokens add column client_platform text not null default 'android'
 check(client_platform in ('android','web'));
create function classmate.register_web_device_token(target_token text) returns void
language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 if target_token is null or length(target_token)<20 or length(target_token)>4096 then raise exception 'Invalid FCM token'; end if;
 insert into classmate.device_tokens(profile_id,fcm_token,client_platform)
 values(auth.uid(),target_token,'web') on conflict(fcm_token) do update
 set profile_id=excluded.profile_id,client_platform='web',updated_at=now(),app_version=null;
end $$;
revoke all on function classmate.register_web_device_token(text) from public,anon;
grant execute on function classmate.register_web_device_token(text) to authenticated;
-- Extend the current context rather than duplicating eligibility/lease checks.
create function classmate.web_notification_job_context(target_event uuid,target_hash text,target_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare content jsonb;
begin
 content:=classmate.notification_job_context(target_event,target_hash,target_lease);
 if content is null then return null; end if;
 return content||jsonb_build_object('client_platform',coalesce((select client_platform from classmate.device_tokens where token_hash=target_hash),'android'));
end $$;
revoke all on function classmate.web_notification_job_context(uuid,text,uuid) from public,anon,authenticated;
grant execute on function classmate.web_notification_job_context(uuid,text,uuid) to service_role;
notify pgrst,'reload schema';
commit;
