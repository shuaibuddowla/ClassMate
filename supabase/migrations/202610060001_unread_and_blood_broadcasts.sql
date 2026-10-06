begin;
alter table classmate.blood_requests add column patient_name text check(patient_name is null or length(patient_name) between 1 and 90);
create table classmate.blood_request_reads (
 request_id uuid not null references classmate.blood_requests(id) on delete cascade,
 profile_id uuid not null references classmate.profiles(id) on delete cascade,
 read_at timestamptz not null default now(), primary key(request_id,profile_id)
);
create index blood_reads_profile on classmate.blood_request_reads(profile_id,request_id);
alter table classmate.blood_request_reads enable row level security;
revoke all on classmate.blood_request_reads from public,anon,authenticated;
grant all on classmate.blood_request_reads to service_role;

create function classmate.blood_broadcast_allowed(target_request uuid,target_profile uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select exists(select 1 from classmate.blood_requests r join classmate.profiles p on p.id=target_profile
 where r.id=target_request and r.status='open' and r.verified_at is not null and r.needed_by>now()
 and (r.audience='university' or r.batch_id=p.batch_id) and p.verification_status='active' and (
 (p.role='student' and exists(select 1 from classmate.batches b where b.id=p.batch_id and b.is_active)) or
 (p.role='teacher' and exists(select 1 from classmate.teacher_allowlist t where t.email=p.email and t.active)) or
 (p.role='admin' and exists(select 1 from classmate.app_owners o where o.email=p.email and o.active))));
$$;
create function classmate.blood_matching_alert(target_request uuid,target_profile uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select exists(select 1 from classmate.blood_requests r join classmate.profiles p on p.id=target_profile
 where r.id=target_request and classmate.blood_group_matches(p.blood_group,r.blood_group,r.compatible_donors)
 and not exists(select 1 from classmate.blood_donations d where d.profile_id=p.id and d.donated_on>(now() at time zone 'Asia/Dhaka')::date-120));
$$;
-- Keep donor enrollment and voluntary contact sharing separate from general blood alerts.
create or replace function classmate.blood_alert_allowed(target_request uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select classmate.is_active() and classmate.blood_broadcast_allowed(target_request,auth.uid());
$$;
drop function classmate.create_blood_request(uuid,uuid,text,text,integer,timestamptz,text,text,boolean);
create function classmate.create_blood_request(request_key uuid,target_batch uuid,target_group text,target_hospital text,target_units integer,
 target_deadline timestamptz,target_phone text,target_audience text default 'university',allow_compatible boolean default false,target_patient_name text default null) returns uuid
language plpgsql security definer set search_path='' as $$
declare old classmate.blood_requests%rowtype;
begin
 if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then raise exception 'Active batch access required' using errcode='42501'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,50));
 select * into old from classmate.blood_requests where id=request_key;
 if found then if old.created_by<>auth.uid() then raise exception 'Request ID already used' using errcode='42501'; end if; return old.id; end if;
 if target_audience is distinct from 'university' then raise exception 'New blood requests are university-wide. Choose University-wide.'; end if;
 if (select count(*) from classmate.blood_requests where created_by=auth.uid() and created_at>now()-interval '24 hours')>=3 then raise exception 'You can submit at most three requests per day'; end if;
 if target_deadline<=now() or target_deadline>now()+interval '7 days' then raise exception 'Choose a deadline within the next seven days'; end if;
 insert into classmate.blood_requests(id,created_by,batch_id,audience,blood_group,hospital,units,needed_by,attendant_phone,compatible_donors,patient_name)
 values(request_key,auth.uid(),target_batch,'university',target_group,trim(target_hospital),target_units,target_deadline,target_phone,allow_compatible,nullif(trim(target_patient_name),''));
 return request_key;
end $$;
alter function classmate.blood_request_details(uuid) rename to blood_request_details_v1;
create function classmate.blood_request_details(target_request uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 result:=classmate.blood_request_details_v1(target_request);
 return result||jsonb_build_object('patient_name',(select patient_name from classmate.blood_requests where id=target_request),
 'requester_name',(select p.full_name from classmate.blood_requests r join classmate.profiles p on p.id=r.created_by where r.id=target_request),
 'read_by_me',exists(select 1 from classmate.blood_request_reads where request_id=target_request and profile_id=auth.uid()));
end $$;
alter function classmate.blood_request_feed(integer) rename to blood_request_feed_v1;
create function classmate.blood_request_feed(result_offset integer default 0) returns jsonb
language plpgsql security definer set search_path='' as $$
declare feed jsonb;
begin
 feed:=classmate.blood_request_feed_v1(result_offset);
 return coalesce((select jsonb_agg(value||jsonb_build_object('read_by_me',exists(select 1 from classmate.blood_request_reads where request_id=(value->>'id')::uuid and profile_id=auth.uid()))) from jsonb_array_elements(feed)),'[]'::jsonb);
end $$;
create function classmate.mark_blood_request_read(target_request uuid) returns void
language plpgsql security definer set search_path='' as $$
begin
 perform classmate.blood_request_details(target_request);
 insert into classmate.blood_request_reads(request_id,profile_id) values(target_request,auth.uid()) on conflict do nothing;
end $$;
create function classmate.unread_activity(target_batch uuid) returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
 if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Active batch access required' using errcode='42501'; end if;
 return jsonb_build_object('notices',(select count(*) from classmate.notices n where n.batch_id=target_batch and classmate.can_read_notice(n.id)
 and not exists(select 1 from classmate.notice_reads r where r.notice_id=n.id and r.profile_id=auth.uid())),
 'blood_requests',(select count(*) from classmate.blood_requests r where r.created_by<>auth.uid() and classmate.blood_broadcast_allowed(r.id,auth.uid())
 and not exists(select 1 from classmate.blood_request_reads s where s.request_id=r.id and s.profile_id=auth.uid())));
end $$;
create function classmate.unread_blood_requests() returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 return coalesce((select jsonb_agg(to_jsonb(q)) from(select r.id,r.blood_group,r.hospital,r.units,r.needed_by
 from classmate.blood_requests r where r.created_by<>auth.uid() and classmate.blood_broadcast_allowed(r.id,auth.uid())
 and not exists(select 1 from classmate.blood_request_reads s where s.request_id=r.id and s.profile_id=auth.uid())
 order by r.needed_by,r.created_at limit 5) q),'[]'::jsonb);
end $$;
create or replace function classmate.notification_recipients(target_event uuid) returns table(profile_id uuid,fcm_token text)
language sql stable security definer set search_path='' as $$
 select * from classmate.academic_notification_recipients(target_event)
 union select p.id,d.fcm_token from classmate.notification_outbox e join classmate.profiles p on p.verification_status='active'
 join classmate.device_tokens d on d.profile_id=p.id where e.id=target_event and e.kind='blood_request' and classmate.blood_broadcast_allowed(e.record_id,p.id);
$$;
create or replace function classmate.notification_device_allowed(target_event uuid,target_profile uuid,target_hash text) returns boolean
language sql stable security definer set search_path='' as $$
 select classmate.academic_notification_device_allowed(target_event,target_profile,target_hash) or exists(
 select 1 from classmate.notification_outbox e join classmate.device_tokens d on d.token_hash=target_hash and d.profile_id=target_profile
 where e.id=target_event and e.kind='blood_request' and classmate.blood_broadcast_allowed(e.record_id,target_profile));
$$;
create or replace function classmate.notification_job_context(target_event uuid,target_hash text,target_lease uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype; r classmate.blood_requests%rowtype; matched boolean; own_group text;
begin
 if not exists(select 1 from classmate.notification_outbox where id=target_event and kind='blood_request') then return classmate.academic_notification_job_context(target_event,target_hash,target_lease); end if;
 select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' and lease_until>now();
 if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
 select r0.* into r from classmate.blood_requests r0 join classmate.notification_outbox e on e.record_id=r0.id where e.id=target_event;
 matched:=classmate.blood_matching_alert(r.id,j.profile_id);
 select blood_group into own_group from classmate.profiles where id=j.profile_id;
 return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=target_hash and profile_id=j.profile_id),
 'batch_id',(select batch_id from classmate.profiles where id=j.profile_id),'title',case when matched then 'Urgent: ' else '' end||r.blood_group||' blood needed',
 'body',case when matched then 'Your profile lists '||own_group||'. Can you help? Contact the attendant at '||r.hospital||'.'
 else 'Know a friend or someone who might help? '||r.units||' unit(s) needed at '||r.hospital||'. Open for contact details.' end,
 'blood_match',matched,'expires_at',r.needed_by,'failures',j.failures);
end $$;
revoke all on function classmate.blood_request_details_v1(uuid),classmate.blood_request_feed_v1(integer) from public,anon,authenticated;
do $$ declare f record;begin
 for f in select p.oid::regprocedure signature,p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='classmate' and p.proname in('blood_broadcast_allowed','blood_matching_alert','create_blood_request','blood_request_details','blood_request_feed','mark_blood_request_read','unread_activity','unread_blood_requests') loop
 execute format('revoke all on function %s from public,anon,authenticated',f.signature);
 if f.proname in('blood_broadcast_allowed','blood_matching_alert') then execute format('grant execute on function %s to service_role',f.signature);
 else execute format('grant execute on function %s to authenticated',f.signature);end if;
 end loop;
end $$;
notify pgrst,'reload schema';
commit;
