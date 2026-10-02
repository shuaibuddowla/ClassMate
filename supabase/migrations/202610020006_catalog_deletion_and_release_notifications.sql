begin;
-- Global deletion is atomic in Postgres. Protected objects are removed by a durable retry queue.
create table classmate.resource_cleanup_jobs (
  object_key text primary key, created_at timestamptz not null default now(),
  attempts integer not null default 0, last_error text
);
alter table classmate.resource_cleanup_jobs enable row level security;
revoke all on classmate.resource_cleanup_jobs from public,anon,authenticated;
grant all on classmate.resource_cleanup_jobs to service_role;

create function classmate.course_deletion_preview(target_course uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare offerings uuid[];
begin
  if not classmate.is_owner() or not classmate.is_active() then raise exception 'Administrator required' using errcode='42501'; end if;
  select coalesce(array_agg(id),'{}'::uuid[]) into offerings from classmate.semester_courses where course_id=target_course;
  return jsonb_build_object('batches',(select count(distinct s.batch_id) from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id where sc.course_id=target_course),
    'periods',(select count(*) from classmate.routine_slots where semester_course_id=any(offerings)),
    'files',(select count(*) from classmate.file_metadata where semester_course_id=any(offerings)),
    'notices',(select count(*) from classmate.notices where semester_course_id=any(offerings) or resource_id in (select id from classmate.file_metadata where semester_course_id=any(offerings))));
end $$;
create function classmate.delete_global_course(target_course uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare offerings uuid[]; files uuid[]; removed classmate.courses%rowtype; impact jsonb;
begin
  if not classmate.is_owner() or not classmate.is_active() then raise exception 'Administrator required' using errcode='42501'; end if;
  select * into removed from classmate.courses where id=target_course for update;
  if not found then return jsonb_build_object('deleted',true); end if;
  -- Locks conflict with FK inserts, preventing new linked records during deletion.
  perform 1 from classmate.semester_courses where course_id=target_course for update;
  select coalesce(array_agg(id),'{}'::uuid[]) into offerings from classmate.semester_courses where course_id=target_course;
  perform 1 from classmate.file_metadata where semester_course_id=any(offerings) for update;
  select coalesce(array_agg(id),'{}'::uuid[]) into files from classmate.file_metadata where semester_course_id=any(offerings);
  impact:=classmate.course_deletion_preview(target_course);
  insert into classmate.resource_cleanup_jobs(object_key) select r2_object_key from classmate.file_metadata where id=any(files) on conflict do nothing;
  delete from classmate.notification_outbox where semester_course_id=any(offerings)
    or (kind='notice' and record_id in(select id from classmate.notices where semester_course_id=any(offerings) or resource_id=any(files)))
    or (kind='file' and record_id=any(files));
  delete from classmate.notices where semester_course_id=any(offerings) or resource_id=any(files);
  delete from classmate.file_metadata where id=any(files);
  delete from classmate.class_changes where semester_course_id=any(offerings);
  delete from classmate.routine_slots where semester_course_id=any(offerings);
  delete from classmate.teacher_course_assignments where semester_course_id=any(offerings);
  delete from classmate.semester_courses where id=any(offerings);
  delete from classmate.courses where id=target_course;
  insert into classmate.audit_log(actor_id,action,target_id,details) values(auth.uid(),'delete_global_course',target_course,impact || jsonb_build_object('course_code',removed.course_code,'course_title',removed.course_title));
  perform classmate.request_notification_dispatch();
  return impact || jsonb_build_object('deleted',true);
end $$;
revoke all on function classmate.course_deletion_preview(uuid),classmate.delete_global_course(uuid) from public,anon;
grant execute on function classmate.course_deletion_preview(uuid),classmate.delete_global_course(uuid) to authenticated;

create table classmate.app_releases (
  id uuid primary key default gen_random_uuid(), version_code bigint not null unique check(version_code>0),
  version_name text not null, created_at timestamptz not null default now()
);
alter table classmate.app_releases enable row level security;
revoke all on classmate.app_releases from public,anon,authenticated;
grant all on classmate.app_releases to service_role;
alter table classmate.notification_outbox alter column batch_id drop not null;
alter table classmate.notification_outbox alter column department_id drop not null;
alter table classmate.notification_outbox add constraint notification_scope_required check(kind='app_update' or (batch_id is not null and department_id is not null));
alter function classmate.notification_recipients(uuid) rename to batch_notification_recipients;
create function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid,fcm_token text) language sql stable security definer set search_path='' as $$
  select * from classmate.batch_notification_recipients(target_event)
  union
  select p.id,dt.fcm_token from classmate.notification_outbox e
  join classmate.profiles p on p.verification_status='active'
  join classmate.device_tokens dt on dt.profile_id=p.id
  where e.id=target_event and e.kind='app_update' and (
    (p.role='student' and exists(select 1 from classmate.batches b where b.id=p.batch_id and b.is_active))
    or (p.role='teacher' and exists(select 1 from classmate.teacher_allowlist t where t.email=p.email and t.active))
    or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.email=p.email and o.active)));
$$;
revoke all on function classmate.notification_recipients(uuid) from public,anon,authenticated;
grant execute on function classmate.notification_recipients(uuid) to service_role;
create function classmate.enqueue_app_release(target_code bigint,target_name text) returns uuid
language plpgsql security definer set search_path='' as $$
declare release_id uuid; event_id uuid;
begin
  insert into classmate.app_releases(version_code,version_name) values(target_code,target_name)
    on conflict(version_code) do nothing;
  select id into release_id from classmate.app_releases where version_code=target_code;
  insert into classmate.notification_outbox(kind,record_id,routing_label) values('app_update',release_id,'all-active-users') on conflict(kind,record_id) do nothing;
  select id into event_id from classmate.notification_outbox where kind='app_update' and record_id=release_id;
  return event_id;
end $$;
revoke all on function classmate.enqueue_app_release(bigint,text) from public,anon,authenticated;
grant execute on function classmate.enqueue_app_release(bigint,text) to service_role;

create table classmate.notification_client_receipts (
  outbox_id uuid not null references classmate.notification_outbox(id) on delete cascade,
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  token_hash text not null, state text not null check(state in('displayed','notifications_disabled','channel_disabled','app_notifications_disabled')),
  app_version integer not null, received_at timestamptz not null default now(),
  primary key(outbox_id,profile_id,token_hash)
);
alter table classmate.notification_client_receipts enable row level security;
revoke all on classmate.notification_client_receipts from public,anon,authenticated;
grant all on classmate.notification_client_receipts to service_role;
create function classmate.report_notification_receipt(target_event uuid,target_hash text,target_state text,target_version integer) returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.is_active() or not exists(select 1 from classmate.notification_recipients(target_event) r where r.profile_id=auth.uid()
      and encode(extensions.digest(r.fcm_token,'sha256'),'hex')=target_hash) then raise exception 'Recipient required' using errcode='42501'; end if;
  insert into classmate.notification_client_receipts(outbox_id,profile_id,token_hash,state,app_version)
    values(target_event,auth.uid(),target_hash,target_state,target_version)
    on conflict(outbox_id,profile_id,token_hash) do update set state=excluded.state,app_version=excluded.app_version,received_at=now();
end $$;
revoke all on function classmate.report_notification_receipt(uuid,text,text,integer) from public,anon;
grant execute on function classmate.report_notification_receipt(uuid,text,text,integer) to authenticated;
create or replace function classmate.request_notification_dispatch() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; dispatcher_secret text; request_id bigint;
begin
  if not exists(select 1 from classmate.notification_outbox where delivered_at is null and attempts<10 and (processing_until is null or processing_until<now()))
     and not exists(select 1 from classmate.resource_cleanup_jobs) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
  select decrypted_secret into dispatcher_secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
  if endpoint is null or dispatcher_secret is null then return null; end if;
  select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',dispatcher_secret),body:='{}'::jsonb,timeout_milliseconds:=10000) into request_id;
  return request_id;
end $$;
notify pgrst,'reload schema';
commit;
