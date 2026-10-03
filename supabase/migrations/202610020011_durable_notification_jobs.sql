begin;
alter table classmate.device_tokens add column token_hash text generated always as (encode(extensions.digest(fcm_token,'sha256'),'hex')) stored;
create unique index device_tokens_hash_idx on classmate.device_tokens(token_hash);
alter table classmate.notification_outbox add column jobs_prepared boolean not null default false;
create table classmate.notification_jobs (
  event_id uuid not null references classmate.notification_outbox(id) on delete cascade,
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  token_hash text not null,
  state text not null default 'pending' check(state in('pending','leased','accepted','invalid','cancelled','failed')),
  failures integer not null default 0, next_attempt_at timestamptz not null default now(),
  lease_id uuid, lease_until timestamptz, last_error text, updated_at timestamptz not null default now(),
  primary key(event_id,token_hash)
);
create index notification_jobs_ready_idx on classmate.notification_jobs(next_attempt_at,event_id) where state in('pending','leased');
create index notification_jobs_state_idx on classmate.notification_jobs(state,event_id);
alter table classmate.notification_jobs enable row level security;
revoke all on classmate.notification_jobs from public,anon,authenticated;
grant all on classmate.notification_jobs to service_role;

-- Evaluate a single device against current membership, without enumerating a whole batch.
create function classmate.notification_device_allowed(target_event uuid,target_profile uuid,target_hash text) returns boolean
language sql stable security definer set search_path='' as $$
  select exists(select 1 from classmate.notification_outbox e
    join classmate.profiles p on p.id=target_profile and p.verification_status='active'
    join classmate.device_tokens dt on dt.profile_id=p.id and dt.token_hash=target_hash
    left join classmate.batches b on b.id=e.batch_id and b.department_id=e.department_id and b.is_active
    where e.id=target_event and (
      (e.kind='app_update' and (
        (p.role='student' and exists(select 1 from classmate.batches own_batch where own_batch.id=p.batch_id and own_batch.is_active))
        or (p.role='teacher' and exists(select 1 from classmate.teacher_allowlist t where t.email=p.email and t.active))
        or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.email=p.email and o.active))))
      or (e.kind<>'app_update' and b.id is not null and (
        (p.role='student' and p.batch_id=b.id and p.department_id=b.department_id)
        or (p.role='teacher' and exists(select 1 from classmate.teacher_course_assignments a
          join classmate.semester_courses sc on sc.id=a.semester_course_id
          join classmate.semesters s on s.id=sc.semester_id
          join classmate.teacher_allowlist t on t.email=p.email and t.department_id=b.department_id and t.active
          where a.teacher_id=p.id and a.active and s.batch_id=b.id and s.status='active'
            and (e.semester_course_id is null or sc.id=e.semester_course_id)))))));
$$;

create function classmate.prepare_notification_jobs(event_limit integer default 5) returns integer
language plpgsql security definer set search_path='' as $$
declare e classmate.notification_outbox%rowtype; prepared integer:=0;
begin
  for e in select * from classmate.notification_outbox where delivered_at is null and not jobs_prepared
    order by (kind='app_update'),created_at for update skip locked limit least(greatest(event_limit,1),20)
  loop
    insert into classmate.notification_jobs(event_id,profile_id,token_hash,state)
      select e.id,r.profile_id,encode(extensions.digest(r.fcm_token,'sha256'),'hex'),
        case when d.outbox_id is not null then 'accepted' else 'pending' end
      from classmate.notification_recipients(e.id) r
      left join classmate.notification_deliveries d on d.outbox_id=e.id and d.token_hash=encode(extensions.digest(r.fcm_token,'sha256'),'hex')
      on conflict do nothing;
    update classmate.notification_outbox set jobs_prepared=true,processing_until=null,last_error=null where id=e.id;
    prepared:=prepared+1;
  end loop;
  return prepared;
end $$;

create function classmate.claim_notification_jobs(claim_size integer default 100)
returns table(event_id uuid,profile_id uuid,token_hash text,lease_id uuid,kind text,record_id uuid,batch_id uuid,department_id uuid)
language sql security definer set search_path='' as $$
  with pending as (
    select j.event_id,j.token_hash from classmate.notification_jobs j join classmate.notification_outbox e on e.id=j.event_id
    where e.delivered_at is null and j.next_attempt_at<=now() and (j.state='pending' or (j.state='leased' and j.lease_until<now()))
    order by (e.kind='app_update'),e.created_at,j.token_hash for update of j skip locked limit least(greatest(claim_size,1),100)
  ), claimed as (
    update classmate.notification_jobs j set state='leased',lease_id=gen_random_uuid(),lease_until=now()+interval '2 minutes',updated_at=now()
    from pending p where j.event_id=p.event_id and j.token_hash=p.token_hash returning j.*
  ) select j.event_id,j.profile_id,j.token_hash,j.lease_id,e.kind,e.record_id,e.batch_id,e.department_id
    from claimed j join classmate.notification_outbox e on e.id=j.event_id order by (e.kind='app_update'),e.created_at,j.token_hash;
$$;

create function classmate.notification_job_context(target_event uuid,target_hash text,target_lease uuid) returns jsonb
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

create function classmate.finish_notification_job(target_event uuid,target_hash text,target_lease uuid,outcome text,error_code text default null,retry_seconds integer default 60) returns boolean
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

create function classmate.complete_notification_events() returns integer language plpgsql security definer set search_path='' as $$
declare completed integer;
begin
  update classmate.notification_outbox e set delivered_at=now(),processing_until=null,
    last_error=case when exists(select 1 from classmate.notification_jobs j where j.event_id=e.id and j.state='failed') then 'Some device deliveries failed; owner retry available' else null end
    where delivered_at is null and jobs_prepared and not exists(select 1 from classmate.notification_jobs j where j.event_id=e.id and j.state in('pending','leased'));
  get diagnostics completed=row_count; return completed;
end $$;

create function classmate.retry_failed_notifications(target_event uuid) returns integer language plpgsql security definer set search_path='' as $$
declare retried integer;
begin
  if not classmate.is_owner() or not classmate.is_active() then raise exception 'Owner required' using errcode='42501'; end if;
  update classmate.notification_jobs set state='pending',failures=0,next_attempt_at=now(),last_error=null where event_id=target_event and state='failed';
  get diagnostics retried=row_count;
  if retried>0 then update classmate.notification_outbox set delivered_at=null,last_error=null where id=target_event; perform classmate.request_notification_dispatch(); end if;
  return retried;
end $$;

-- Cron considers durable progress, not how many invocations an event has consumed.
create or replace function classmate.request_notification_dispatch() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; dispatcher_secret text; request_id bigint;
begin
  if not exists(select 1 from classmate.notification_outbox where delivered_at is null and not jobs_prepared)
    and not exists(select 1 from classmate.notification_jobs where next_attempt_at<=now() and (state='pending' or (state='leased' and lease_until<now()))) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
  select decrypted_secret into dispatcher_secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
  if endpoint is null or dispatcher_secret is null then return null; end if;
  select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',dispatcher_secret),body:='{}'::jsonb,timeout_milliseconds:=10000) into request_id;
  return request_id;
end $$;

revoke all on function classmate.notification_device_allowed(uuid,uuid,text),classmate.prepare_notification_jobs(integer),classmate.claim_notification_jobs(integer),classmate.notification_job_context(uuid,text,uuid),classmate.finish_notification_job(uuid,text,uuid,text,text,integer),classmate.complete_notification_events() from public,anon,authenticated;
grant execute on function classmate.notification_device_allowed(uuid,uuid,text),classmate.prepare_notification_jobs(integer),classmate.claim_notification_jobs(integer),classmate.notification_job_context(uuid,text,uuid),classmate.finish_notification_job(uuid,text,uuid,text,text,integer),classmate.complete_notification_events() to service_role;
revoke all on function classmate.retry_failed_notifications(uuid) from public,anon;
grant execute on function classmate.retry_failed_notifications(uuid) to authenticated;
notify pgrst,'reload schema';
commit;
