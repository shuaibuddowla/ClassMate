"""Transactional staging checks; no FCM calls and no fixtures remain."""
import sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
query(STAGING,"""begin;
do $$
declare person uuid; tok text := 'classmate-origin-check-'||gen_random_uuid(); android_tok text := 'classmate-android-check-'||gen_random_uuid(); h text; android_hash text; event uuid:=gen_random_uuid(); release uuid:=gen_random_uuid(); lease uuid:=gen_random_uuid(); result jsonb;
begin
 select p.id into person from classmate.profiles p where p.verification_status='active' and ((p.role='student' and exists(select 1 from classmate.batches b where b.id=p.batch_id and b.is_active)) or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.email=p.email and o.active))) limit 1;
 if person is null then raise exception 'Staging fixture account required'; end if;
 perform set_config('request.jwt.claim.sub',person::text,true);
 perform set_config('request.headers','{"origin":"https://classmate-web-mu.vercel.app"}',true);
 begin
  perform classmate.register_web_device_token(tok);
  raise exception 'Old origin was accepted';
 exception when insufficient_privilege then null;
 end;
 perform set_config('request.headers','{"origin":"https://classmate-web-staging.vercel.app"}',true);
 perform classmate.register_web_device_token(tok);
 if not exists(select 1 from classmate.device_tokens where fcm_token=tok and web_origin='https://classmate-web-staging.vercel.app') then raise exception 'Origin not recorded'; end if;
 if not classmate.claim_browser_push_test(tok) then raise exception 'Allowed browser test rejected'; end if;
 perform set_config('request.headers','{"origin":"https://classmate-web-mu.vercel.app"}',true);
 update classmate.device_tokens set last_web_test_at=null where fcm_token=tok;
 if classmate.claim_browser_push_test(tok) then raise exception 'Cross-origin test accepted'; end if;
 select token_hash into h from classmate.device_tokens where fcm_token=tok;
 insert into classmate.app_releases(id,version_code,version_name) values(release,987641,'origin-check');
 insert into classmate.notification_outbox(id,kind,record_id,routing_label) values(event,'app_update',release,'origin-check');
 insert into classmate.notification_jobs(event_id,profile_id,token_hash,state,lease_id,lease_until) values(event,person,h,'leased',lease,now()+interval '5 minutes');
 result:=classmate.web_notification_job_context(event,h,lease);
 if result->>'client_platform' is distinct from 'web' then raise exception 'Current web registration excluded'; end if;
 perform classmate.register_device_token(android_tok,22);
 select token_hash into android_hash from classmate.device_tokens where fcm_token=android_tok;
 insert into classmate.notification_jobs(event_id,profile_id,token_hash,state,lease_id,lease_until) values(event,person,android_hash,'leased',lease,now()+interval '5 minutes');
 if classmate.web_notification_job_context(event,android_hash,lease)->>'client_platform' is distinct from 'android' then raise exception 'Simultaneous Android registration excluded'; end if;
 if classmate.web_notification_job_context(event,h,lease)->>'client_platform' is distinct from 'web' then raise exception 'Android registration replaced web registration'; end if;
 update classmate.device_tokens set web_origin=null where fcm_token=tok;
 if classmate.web_notification_job_context(event,h,lease) is not null then raise exception 'Legacy registration eligible'; end if;
 update classmate.device_tokens set web_origin='https://classmate-web-mu.vercel.app' where fcm_token=tok;
 if classmate.web_notification_job_context(event,h,lease) is not null then raise exception 'Old origin eligible'; end if;
 update classmate.device_tokens set client_platform='android',web_origin=null where fcm_token=tok;
 if classmate.web_notification_job_context(event,h,lease)->>'client_platform' is distinct from 'android' then raise exception 'Android delivery excluded'; end if;
end $$;
rollback;
""")
print('Passed: current origin registration/delivery; legacy and old origin excluded; cross-origin test denied; Android preserved. Rolled back all fixtures. No push sent.')
