begin;
create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron;
alter table classmate.notification_outbox add column processing_until timestamptz;
create table classmate.notification_deliveries (
  outbox_id uuid not null references classmate.notification_outbox(id) on delete cascade,
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  token_hash text not null,
  delivered_at timestamptz not null default now(),
  primary key(outbox_id,token_hash)
);
alter table classmate.notification_deliveries enable row level security;
revoke all on classmate.notification_deliveries from public,anon,authenticated;
grant all on classmate.notification_deliveries to service_role;

create function classmate.claim_notification_events(batch_size integer default 20)
returns setof classmate.notification_outbox language sql security definer set search_path='' as $$
  update classmate.notification_outbox o set processing_until=now()+interval '2 minutes',attempts=o.attempts+1
  where o.id in (select id from classmate.notification_outbox
    where delivered_at is null and attempts<10 and (processing_until is null or processing_until<now())
    order by created_at for update skip locked limit least(greatest(batch_size,1),20)) returning o.*;
$$;
create function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid,fcm_token text) language sql stable security definer set search_path='' as $$
  select distinct p.id,dt.fcm_token
  from classmate.notification_outbox e join classmate.batches b on b.id=e.batch_id and b.department_id=e.department_id and b.is_active
  join classmate.profiles p on p.verification_status='active'
  join classmate.device_tokens dt on dt.profile_id=p.id
  where e.id=target_event and (
    (p.role='student' and p.batch_id=b.id and p.department_id=b.department_id)
    or (p.role='teacher' and exists (
      select 1 from classmate.teacher_course_assignments a
      join classmate.semester_courses sc on sc.id=a.semester_course_id
      join classmate.semesters s on s.id=sc.semester_id
      join classmate.teacher_allowlist t on t.email=p.email and t.department_id=b.department_id and t.active
      where a.teacher_id=p.id and a.active and s.batch_id=b.id and s.status='active'
        and (e.semester_course_id is null or sc.id=e.semester_course_id)
    ))
  );
$$;
revoke all on function classmate.claim_notification_events(integer),classmate.notification_recipients(uuid) from public,anon,authenticated;
grant execute on function classmate.claim_notification_events(integer),classmate.notification_recipients(uuid) to service_role;

create function classmate.request_notification_dispatch() returns bigint language plpgsql security definer set search_path='' as $$
declare endpoint text; dispatcher_secret text; request_id bigint;
begin
  if not exists(select 1 from classmate.notification_outbox where delivered_at is null and attempts<10 and (processing_until is null or processing_until<now())) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='classmate_dispatch_url' limit 1;
  select decrypted_secret into dispatcher_secret from vault.decrypted_secrets where name='classmate_dispatch_secret' limit 1;
  if endpoint is null or dispatcher_secret is null then return null; end if;
  select net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-dispatch-secret',dispatcher_secret),body:='{}'::jsonb,timeout_milliseconds:=10000) into request_id;
  return request_id;
end;
$$;
create function classmate.wake_notification_dispatch() returns trigger language plpgsql security definer set search_path='' as $$
begin
  perform classmate.request_notification_dispatch(); return new;
exception when others then
  -- Delivery infrastructure must never prevent the academic write. Cron retries the outbox.
  return new;
end;
$$;
revoke all on function classmate.request_notification_dispatch(),classmate.wake_notification_dispatch() from public,anon,authenticated;
grant execute on function classmate.request_notification_dispatch() to service_role;
create trigger wake_notification_dispatch after insert on classmate.notification_outbox
  for each statement execute function classmate.wake_notification_dispatch();
select cron.schedule('classmate-notification-retry','* * * * *','select classmate.request_notification_dispatch();');
notify pgrst, 'reload schema';
commit;
