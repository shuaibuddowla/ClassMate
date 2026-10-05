begin;
-- Contact information is returned only through authenticated, bounded RPCs.
create table classmate.blood_donor_preferences (
 profile_id uuid primary key references classmate.profiles(id) on delete cascade,
 opted_in boolean not null default false, updated_at timestamptz not null default now()
);
create table classmate.blood_donations (
 id uuid primary key default gen_random_uuid(), profile_id uuid not null references classmate.profiles(id) on delete cascade,
 donated_on date not null check(donated_on >= date '2000-01-01'), created_at timestamptz not null default now(),
 unique(profile_id,donated_on)
);
create index blood_donation_recent on classmate.blood_donations(profile_id,donated_on desc);
create table classmate.blood_requests (
 id uuid primary key, created_by uuid not null references classmate.profiles(id),
 batch_id uuid not null references classmate.batches(id), audience text not null check(audience in('university','batch')),
 blood_group text not null check(blood_group in('A+','A-','B+','B-','AB+','AB-','O+','O-')),
 hospital text not null check(length(hospital) between 3 and 160), units integer not null check(units between 1 and 20),
 needed_by timestamptz not null, attendant_phone text not null check(attendant_phone ~ '^\+[1-9][0-9]{7,14}$'),
 compatible_donors boolean not null default false,
 status text not null default 'pending' check(status in('pending','open','fulfilled','cancelled','rejected')),
 verified_by uuid references classmate.profiles(id), verified_at timestamptz,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 check(status <> 'open' or (verified_by is not null and verified_at is not null))
);
create index blood_request_feed on classmate.blood_requests(created_at desc);
create index blood_request_active on classmate.blood_requests(needed_by,batch_id) where status='open';
create table classmate.blood_responses (
 request_id uuid not null references classmate.blood_requests(id) on delete cascade,
 profile_id uuid not null references classmate.profiles(id) on delete cascade,
 state text not null check(state in('interested','withdrawn')), updated_at timestamptz not null default now(),
 primary key(request_id,profile_id)
);
alter table classmate.blood_donor_preferences enable row level security;
alter table classmate.blood_donations enable row level security;
alter table classmate.blood_requests enable row level security;
alter table classmate.blood_responses enable row level security;
revoke all on classmate.blood_donor_preferences,classmate.blood_donations,classmate.blood_requests,classmate.blood_responses from public,anon,authenticated;
grant all on classmate.blood_donor_preferences,classmate.blood_donations,classmate.blood_requests,classmate.blood_responses to service_role;

-- This is RED-CELL recruitment compatibility, never a transfusion decision.
create function classmate.blood_group_matches(donor text,recipient text,allow_compatible boolean) returns boolean
language sql immutable set search_path='' as $$
 select donor=recipient or (allow_compatible and (
  donor='O-' or (recipient='O+' and donor='O+') or
  (recipient='A-' and donor='A-') or (recipient='A+' and donor in('A-','A+','O+')) or
  (recipient='B-' and donor='B-') or (recipient='B+' and donor in('B-','B+','O+')) or
  (recipient='AB-' and donor in('A-','B-','AB-')) or
  (recipient='AB+' and donor in('A-','A+','B-','B+','AB-','AB+','O+'))));
$$;
create function classmate.blood_can_verify(target_batch uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select classmate.is_active() and (classmate.is_owner() or exists(select 1 from classmate.profiles
 where id=auth.uid() and is_cr and cr_batch_id=target_batch and batch_id=target_batch
 and (cr_valid_until is null or cr_valid_until>now())
 and exists(select 1 from classmate.batches where id=target_batch and is_active)));
$$;
create function classmate.blood_donor_allowed(target_request uuid,target_profile uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select exists(select 1 from classmate.blood_requests r join classmate.profiles p on p.id=target_profile
 join classmate.blood_donor_preferences d on d.profile_id=p.id and d.opted_in
 where r.id=target_request and r.status='open' and r.needed_by>now() and p.verification_status='active'
 and exists(select 1 from classmate.batches b where b.id=p.batch_id and b.is_active)
 and (r.audience='university' or r.batch_id=p.batch_id)
 and p.mobile_number ~ '^\+[1-9][0-9]{7,14}$'
 and classmate.blood_group_matches(p.blood_group,r.blood_group,r.compatible_donors)
 and not exists(select 1 from classmate.blood_donations h where h.profile_id=p.id
  and h.donated_on > (now() at time zone 'Asia/Dhaka')::date-120));
$$;
create function classmate.blood_preferences() returns jsonb language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 return jsonb_build_object('opted_in',coalesce((select opted_in from classmate.blood_donor_preferences where profile_id=auth.uid()),false),
 'last_donation',(select max(donated_on) from classmate.blood_donations where profile_id=auth.uid()),'cooldown_days',120);
end $$;
create function classmate.save_blood_preferences(target_enabled boolean,donation_date date default null) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 if donation_date is not null and (donation_date > (now() at time zone 'Asia/Dhaka')::date or donation_date<date '2000-01-01') then raise exception 'Invalid donation date'; end if;
 if target_enabled and not exists(select 1 from classmate.profiles where id=auth.uid() and blood_group in('A+','A-','B+','B-','AB+','AB-','O+','O-') and mobile_number ~ '^\+[1-9][0-9]{7,14}$') then raise exception 'Complete your blood group and mobile number in Profile first'; end if;
 insert into classmate.blood_donor_preferences(profile_id,opted_in) values(auth.uid(),target_enabled)
 on conflict(profile_id) do update set opted_in=excluded.opted_in,updated_at=now();
 if donation_date is not null then insert into classmate.blood_donations(profile_id,donated_on) values(auth.uid(),donation_date) on conflict do nothing; end if;
 return classmate.blood_preferences();
end $$;
create function classmate.create_blood_request(request_key uuid,target_batch uuid,target_group text,target_hospital text,target_units integer,
 target_deadline timestamptz,target_phone text,target_audience text default 'university',allow_compatible boolean default false) returns uuid
language plpgsql security definer set search_path='' as $$
declare old classmate.blood_requests%rowtype;
begin
 if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then raise exception 'Active batch access required' using errcode='42501'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,50));
 select * into old from classmate.blood_requests where id=request_key;
 if found then
  if old.created_by<>auth.uid() then raise exception 'Request ID already used' using errcode='42501'; end if;
  return old.id;
 end if;
 if (select count(*) from classmate.blood_requests where created_by=auth.uid() and created_at>now()-interval '24 hours')>=3 then raise exception 'You can submit at most three requests per day'; end if;
 if target_deadline<=now() or target_deadline>now()+interval '7 days' then raise exception 'Choose a deadline within the next seven days'; end if;
 insert into classmate.blood_requests(id,created_by,batch_id,audience,blood_group,hospital,units,needed_by,attendant_phone,compatible_donors)
 values(request_key,auth.uid(),target_batch,target_audience,target_group,trim(target_hospital),target_units,target_deadline,target_phone,allow_compatible);
 return request_key;
end $$;
create function classmate.set_blood_request_status(target_request uuid,target_status text) returns void
language plpgsql security definer set search_path='' as $$
declare r classmate.blood_requests%rowtype;
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 select * into r from classmate.blood_requests where id=target_request for update;
 if not found then raise exception 'Request not found'; end if;
 if target_status in('open','rejected') then
  if not classmate.blood_can_verify(r.batch_id) then raise exception 'Admin or request batch CR verification required' using errcode='42501'; end if;
  if r.status<>'pending' or r.needed_by<=now() then raise exception 'Only live pending requests can be verified'; end if;
 elsif target_status in('fulfilled','cancelled') then
  if r.created_by<>auth.uid() and not classmate.blood_can_verify(r.batch_id) then raise exception 'Request owner required' using errcode='42501'; end if;
  if r.status not in('pending','open') then raise exception 'Request is already closed'; end if;
 else raise exception 'Invalid status'; end if;
 update classmate.blood_requests set status=target_status,updated_at=now(),
 verified_by=case when target_status='open' then auth.uid() else verified_by end,
 verified_at=case when target_status='open' then now() else verified_at end where id=r.id;
 if target_status='open' then insert into classmate.notification_outbox(kind,record_id,routing_label)
 values('blood_request',r.id,'opted-in-compatible-donors') on conflict do nothing; end if;
 insert into classmate.audit_log(actor_id,action,target_id,details) values(auth.uid(),'blood_request_'||target_status,r.id,'{}');
end $$;
create function classmate.blood_request_feed(result_offset integer default 0) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 return coalesce((select jsonb_agg(to_jsonb(q)) from (select r.id,r.blood_group,r.hospital,r.units,r.needed_by,
 case when r.status in('pending','open') and r.needed_by<=now() then 'expired' else r.status end status,
 r.audience,r.compatible_donors,r.created_at,r.created_by=auth.uid() is_mine,
 classmate.blood_can_verify(r.batch_id) can_verify,
 classmate.blood_donor_allowed(r.id,auth.uid()) can_donate,
 (select state from classmate.blood_responses where request_id=r.id and profile_id=auth.uid()) my_response,
 (select count(*) from classmate.blood_responses where request_id=r.id and state='interested') volunteers
 from classmate.blood_requests r where
 (r.created_by=auth.uid() or classmate.blood_can_verify(r.batch_id) or
 (r.status='open' and r.needed_by>now() and (r.audience='university' or exists(select 1 from classmate.profiles where id=auth.uid() and batch_id=r.batch_id))))
 order by r.created_at desc limit 20 offset least(greatest(result_offset,0),10000)) q),'[]'::jsonb);
end $$;
create function classmate.blood_request_details(target_request uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare r classmate.blood_requests%rowtype; manage boolean;
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 select * into r from classmate.blood_requests where id=target_request;
 manage:=r.created_by=auth.uid() or classmate.blood_can_verify(r.batch_id);
 if r.id is null or not (manage or (r.status='open' and r.needed_by>now() and (r.audience='university' or exists(select 1 from classmate.profiles where id=auth.uid() and batch_id=r.batch_id)))) then raise exception 'Request unavailable' using errcode='42501'; end if;
 return jsonb_build_object('id',r.id,'blood_group',r.blood_group,'hospital',r.hospital,'units',r.units,'needed_by',r.needed_by,
 'status',case when r.status in('pending','open') and r.needed_by<=now() then 'expired' else r.status end,
 'can_verify',classmate.blood_can_verify(r.batch_id),'my_response',(select state from classmate.blood_responses where request_id=r.id and profile_id=auth.uid()),
 'attendant_phone',r.attendant_phone,'can_manage',manage,'can_donate',classmate.blood_donor_allowed(r.id,auth.uid()),
 'volunteers',case when manage then coalesce((select jsonb_agg(to_jsonb(v)) from (select p.id,p.full_name,p.mobile_number,s.updated_at
 from classmate.blood_responses s join classmate.profiles p on p.id=s.profile_id
 where s.request_id=r.id and s.state='interested' order by s.updated_at limit 50) v),'[]'::jsonb) else '[]'::jsonb end);
end $$;
create function classmate.respond_blood_request(target_request uuid,target_response text) returns void
language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 perform 1 from classmate.blood_requests where id=target_request for update;
 if target_response='interested' then
  if not classmate.blood_donor_allowed(target_request,auth.uid()) then raise exception 'Request closed or donor preferences, blood group or donation interval do not match'; end if;
 elsif target_response='withdrawn' then
  if not exists(select 1 from classmate.blood_responses where request_id=target_request and profile_id=auth.uid()) then raise exception 'No response to withdraw'; end if;
 else raise exception 'Invalid response'; end if;
 insert into classmate.blood_responses(request_id,profile_id,state) values(target_request,auth.uid(),target_response)
 on conflict(request_id,profile_id) do update set state=excluded.state,updated_at=now();
end $$;
create function classmate.blood_alert_allowed(target_request uuid) returns boolean language sql stable security definer set search_path='' as $$
 select classmate.is_active() and classmate.blood_donor_allowed(target_request,auth.uid());
$$;

alter table classmate.notification_outbox drop constraint notification_scope_required;
alter table classmate.notification_outbox add constraint notification_scope_required check(kind in('app_update','blood_request') or (batch_id is not null and department_id is not null));
alter function classmate.notification_recipients(uuid) rename to academic_notification_recipients;
create function classmate.notification_recipients(target_event uuid) returns table(profile_id uuid,fcm_token text)
language sql stable security definer set search_path='' as $$
 select * from classmate.academic_notification_recipients(target_event)
 union select p.id,d.fcm_token from classmate.notification_outbox e join classmate.profiles p on p.verification_status='active'
 join classmate.device_tokens d on d.profile_id=p.id
 where e.id=target_event and e.kind='blood_request' and classmate.blood_donor_allowed(e.record_id,p.id);
$$;
alter function classmate.notification_device_allowed(uuid,uuid,text) rename to academic_notification_device_allowed;
create function classmate.notification_device_allowed(target_event uuid,target_profile uuid,target_hash text) returns boolean
language sql stable security definer set search_path='' as $$
 select classmate.academic_notification_device_allowed(target_event,target_profile,target_hash) or exists(
 select 1 from classmate.notification_outbox e join classmate.device_tokens d on d.token_hash=target_hash and d.profile_id=target_profile
 where e.id=target_event and e.kind='blood_request' and classmate.blood_donor_allowed(e.record_id,target_profile));
$$;
alter function classmate.notification_job_context(uuid,text,uuid) rename to academic_notification_job_context;
create function classmate.notification_job_context(target_event uuid,target_hash text,target_lease uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype; r classmate.blood_requests%rowtype;
begin
 if not exists(select 1 from classmate.notification_outbox where id=target_event and kind='blood_request') then
 return classmate.academic_notification_job_context(target_event,target_hash,target_lease); end if;
 select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash and lease_id=target_lease and state='leased' and lease_until>now();
 if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
 select r0.* into r from classmate.blood_requests r0 join classmate.notification_outbox e on e.record_id=r0.id where e.id=target_event;
 return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=target_hash and profile_id=j.profile_id),
 'batch_id',(select batch_id from classmate.profiles where id=j.profile_id),'title','Urgent: '||r.blood_group||' blood needed',
 'body',r.units||' unit(s) · '||r.hospital||'. Open to volunteer or call the attendant.', 'expires_at',r.needed_by,'failures',j.failures);
end $$;
-- Function bodies resolve names at runtime; browser origin gating remains intact.
do $$ declare f record; begin
 for f in select p.oid::regprocedure signature,p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='classmate' and p.proname in('blood_group_matches','blood_can_verify','blood_donor_allowed','blood_preferences','save_blood_preferences','create_blood_request','set_blood_request_status','blood_request_feed','blood_request_details','respond_blood_request','blood_alert_allowed','notification_recipients','notification_device_allowed','notification_job_context') loop
 execute format('revoke all on function %s from public,anon,authenticated',f.signature);
 if f.proname in('notification_recipients','notification_device_allowed','notification_job_context','blood_donor_allowed','blood_group_matches') then
 execute format('grant execute on function %s to service_role',f.signature);
 else execute format('grant execute on function %s to authenticated',f.signature); end if;
 end loop;
end $$;
notify pgrst,'reload schema';
commit;
