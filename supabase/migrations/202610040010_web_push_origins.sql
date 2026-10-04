begin;
-- Origins are server-managed. Legacy registrations remain stored, but cannot
-- receive push until re-registered from the current approved site.
alter table classmate.device_tokens add column web_origin text;
create table classmate.web_push_origins (
 origin text primary key check (origin ~ '^https?://[^/]+$')
);
alter table classmate.web_push_origins enable row level security;
revoke all on classmate.web_push_origins from public,anon,authenticated;
grant all on classmate.web_push_origins to service_role;
insert into classmate.web_push_origins values ('https://classmatebd.vercel.app');

create or replace function classmate.register_web_device_token(target_token text) returns void
language plpgsql security definer set search_path='' as $$
declare browser_origin text := (coalesce(nullif(current_setting('request.headers',true),''),'{}')::jsonb)->>'origin';
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 if not exists(select 1 from classmate.web_push_origins where origin=browser_origin) then
  raise exception 'Open the current ClassMate website to enable notifications' using errcode='42501';
 end if;
 if target_token is null or length(target_token)<20 or length(target_token)>4096 then raise exception 'Invalid FCM token'; end if;
 insert into classmate.device_tokens(profile_id,fcm_token,client_platform,web_origin)
 values(auth.uid(),target_token,'web',browser_origin) on conflict(fcm_token) do update
 set profile_id=excluded.profile_id,client_platform='web',web_origin=excluded.web_origin,updated_at=now(),app_version=null;
end $$;

create or replace function classmate.web_notification_job_context(target_event uuid,target_hash text,target_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare content jsonb; platform text; browser_origin text;
begin
 content:=classmate.notification_job_context(target_event,target_hash,target_lease);
 if content is null then return null; end if;
 select client_platform,web_origin into platform,browser_origin from classmate.device_tokens where token_hash=target_hash;
 if platform='web' and not exists(select 1 from classmate.web_push_origins where origin=browser_origin) then return null; end if;
 return content||jsonb_build_object('client_platform',coalesce(platform,'android'));
end $$;

create or replace function classmate.claim_browser_push_test(target_token text) returns boolean
language plpgsql security definer set search_path='' as $$
declare browser_origin text := (coalesce(nullif(current_setting('request.headers',true),''),'{}')::jsonb)->>'origin';
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 update classmate.device_tokens d set last_web_test_at=now() where profile_id=auth.uid()
 and fcm_token=target_token and client_platform='web' and web_origin=browser_origin
 and exists(select 1 from classmate.web_push_origins where origin=browser_origin)
 and (last_web_test_at is null or last_web_test_at<now()-interval '1 minute');
 return found;
end $$;
notify pgrst,'reload schema';
commit;
