begin;

create table classmate.student_bans (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  banned_by uuid not null references classmate.profiles(id) on delete restrict,
  reason text not null check (length(trim(reason)) between 3 and 500),
  starts_at timestamptz not null default now(),
  ends_at timestamptz not null,
  lifted_at timestamptz,
  lifted_by uuid references classmate.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  check (ends_at > starts_at)
);
alter table classmate.notification_outbox add column recipient_profile_id uuid references classmate.profiles(id) on delete cascade;
create index student_bans_active_profile on classmate.student_bans(profile_id, ends_at)
  where lifted_at is null;

-- Elect the first verified student atomically unless a CR is preassigned.
create function classmate.auto_assign_first_batch_cr()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.role='student' and new.verification_status='active' and new.batch_id is not null then
    perform pg_advisory_xact_lock(hashtextextended(new.batch_id::text, 0));
    if not exists(select 1 from classmate.profiles p where p.batch_id=new.batch_id
      and p.is_cr and p.cr_batch_id=new.batch_id and p.verification_status='active'
      and (p.cr_valid_until is null or p.cr_valid_until>now())) then
      update classmate.profiles set is_cr=true,cr_batch_id=new.batch_id,cr_valid_until=null,
        updated_at=now() where id=new.id;
    end if;
  end if;
  return new;
end;
$$;
create trigger auto_assign_first_batch_cr after insert or update of batch_id,verification_status,role
  on classmate.profiles for each row execute function classmate.auto_assign_first_batch_cr();
alter table classmate.student_bans enable row level security;
revoke all on classmate.student_bans from public, anon, authenticated;
grant select on classmate.student_bans to authenticated;

create function classmate.active_student_ban()
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('ends_at', b.ends_at, 'reason', b.reason)
  from classmate.student_bans b
  where b.profile_id = auth.uid() and b.lifted_at is null
    and b.starts_at <= now() and b.ends_at > now()
  order by b.ends_at desc limit 1;
$$;

create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid,fcm_token text) language sql stable security definer set search_path='' as $$
  select distinct p.id,dt.fcm_token
  from classmate.notification_outbox e
  join classmate.profiles p on p.verification_status='active'
  join classmate.device_tokens dt on dt.profile_id=p.id
  left join classmate.batches b on b.id=e.batch_id and b.department_id=e.department_id and b.is_active
  where e.id=target_event and (
    p.id=e.recipient_profile_id or
    ((p.role='student' and p.batch_id=b.id and p.department_id=b.department_id)
      or (p.role='teacher' and exists(select 1 from classmate.teacher_course_assignments a
        join classmate.semester_courses sc on sc.id=a.semester_course_id
        join classmate.semesters s on s.id=sc.semester_id
        join classmate.teacher_allowlist t on t.email=p.email and t.department_id=b.department_id and t.active
        where a.teacher_id=p.id and a.active and s.batch_id=b.id and s.status='active'
          and (e.semester_course_id is null or sc.id=e.semester_course_id))))
  );
$$;
create or replace function classmate.notification_device_allowed(target_event uuid,target_profile uuid,target_hash text)
returns boolean language sql stable security definer set search_path='' as $$
 select classmate.academic_notification_device_allowed(target_event,target_profile,target_hash) or exists(
   select 1 from classmate.notification_outbox e join classmate.device_tokens d
     on d.token_hash=target_hash and d.profile_id=target_profile
   where e.id=target_event and e.recipient_profile_id=target_profile
 );
$$;

-- All RPCs and row policies that use is_active now deny a live ban immediately.
create or replace function classmate.is_active()
returns boolean language plpgsql stable security definer set search_path = '' as $$
begin
  return exists (
    select 1 from classmate.profiles p where p.id=(select auth.uid())
      and p.verification_status='active'
      and (p.role='student' or (p.role='admin' and classmate.is_owner())
        or (p.role='teacher' and exists(select 1 from classmate.teacher_allowlist a
          where a.email=p.email and a.active)))
      and not exists(select 1 from classmate.student_bans b where b.profile_id=p.id
        and b.lifted_at is null and b.starts_at<=now() and b.ends_at>now())
  );
end;
$$;
revoke all on function classmate.is_active() from public,anon;
grant execute on function classmate.is_active() to authenticated;

create function classmate.manage_student_ban(
  target_profile uuid, target_days integer, target_reason text, target_lift boolean default false
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare caller classmate.profiles%rowtype; target classmate.profiles%rowtype; ban_id uuid;
begin
  select * into caller from classmate.profiles where id=auth.uid() and verification_status='active';
  select * into target from classmate.profiles where id=target_profile and role='student'
    and verification_status='active';
  if caller.id is null or target.id is null or caller.id=target.id then
    raise exception 'Active caller and target student required' using errcode='42501';
  end if;
  if caller.role='admin' and not classmate.is_owner() then raise exception 'Owner permission required' using errcode='42501'; end if;
  if not classmate.is_active() then raise exception 'Caller account is inactive' using errcode='42501'; end if;
  if caller.role <> 'admin' and not (
    caller.role='student' and caller.is_cr and caller.cr_batch_id=caller.batch_id
    and caller.batch_id=target.batch_id
    and (caller.cr_valid_until is null or caller.cr_valid_until>now())
  ) then raise exception 'Batch CR or admin permission required' using errcode='42501'; end if;
  if caller.role <> 'admin' and target.batch_id is distinct from caller.batch_id then
    raise exception 'You can only manage students in your own batch' using errcode='42501';
  end if;
  if target_lift then
    update classmate.student_bans set lifted_at=now(),lifted_by=auth.uid()
      where profile_id=target_profile and batch_id=target.batch_id and lifted_at is null
        and ends_at>now();
    insert into classmate.notification_outbox(kind,record_id,department_id,batch_id,routing_label,recipient_profile_id)
      select 'student_ban_lifted',id,target.department_id,target.batch_id,classmate.routing_label(target.batch_id),target_profile
      from classmate.student_bans where profile_id=target_profile and lifted_by=auth.uid()
        order by lifted_at desc limit 1;
    insert into classmate.audit_log(actor_id,action,target_id)
      values(auth.uid(),'lift_student_ban',target_profile);
    return jsonb_build_object('lifted',true);
  end if;
  if target_days not between 1 and 30 or length(trim(coalesce(target_reason,''))) not between 3 and 500 then
    raise exception 'Ban duration must be 1 to 30 days and include a reason';
  end if;
  if caller.role='student' and target_days>7 then
    raise exception 'CRs may issue bans for up to 7 days; ask an admin for a longer ban' using errcode='42501';
  end if;
  update classmate.student_bans set lifted_at=now(),lifted_by=auth.uid()
    where profile_id=target_profile and lifted_at is null and ends_at>now();
  insert into classmate.student_bans(profile_id,batch_id,banned_by,reason,ends_at)
    values(target_profile,target.batch_id,auth.uid(),trim(target_reason),now()+make_interval(days=>target_days))
    returning id into ban_id;
  insert into classmate.notification_outbox(kind,record_id,department_id,batch_id,routing_label,recipient_profile_id)
    values('student_ban',ban_id,target.department_id,target.batch_id,classmate.routing_label(target.batch_id),target_profile);
  insert into classmate.audit_log(actor_id,action,target_id)
    values(auth.uid(),'ban_student',target_profile);
  return jsonb_build_object('id',ban_id,'ends_at',now()+make_interval(days=>target_days));
end;
$$;

create function classmate.student_ban_notification_job_context(target_event uuid,target_hash text,target_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype; e classmate.notification_outbox%rowtype; ban classmate.student_bans%rowtype;
begin
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash
    and lease_id=target_lease and state='leased' and lease_until>now();
  if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
  select * into e from classmate.notification_outbox where id=target_event and kind in ('student_ban','student_ban_lifted');
  if not found then return null; end if;
  select * into ban from classmate.student_bans where id=e.record_id;
  return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
    'batch_id',e.batch_id,
    'title',case when e.kind='student_ban_lifted' then 'ClassMate ban lifted' else 'ClassMate temporary ban' end,
    'body',case when e.kind='student_ban_lifted' then 'Your temporary ClassMate ban has been lifted.' else
      'Access is restricted until '||to_char(ban.ends_at at time zone 'Asia/Dhaka','DD Mon YYYY HH12:MI AM')||'. Contact your CR or administrator.' end,
    'failures',j.failures);
end;
$$;
create or replace function classmate.web_notification_job_context(target_event uuid,target_hash text,target_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare content jsonb; platform text; browser_origin text;
begin
 content:=classmate.notification_job_context(target_event,target_hash,target_lease);
 if content is null then content:=classmate.student_ban_notification_job_context(target_event,target_hash,target_lease); end if;
 if content is null then return null; end if;
 select client_platform,web_origin into platform,browser_origin from classmate.device_tokens where token_hash=target_hash;
 if platform='web' and not exists(select 1 from classmate.web_push_origins where origin=browser_origin) then return null; end if;
 return content||jsonb_build_object('client_platform',coalesce(platform,'android'));
end;
$$;

create function classmate.transfer_batch_cr(target_profile uuid)
returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare caller classmate.profiles%rowtype; target classmate.profiles%rowtype;
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  select * into caller from classmate.profiles where id=auth.uid() and role='student'
    and verification_status='active' and is_cr and cr_batch_id=batch_id and cr_batch_id is not null
    and (cr_valid_until is null or cr_valid_until>now()) for update;
  if caller.id is null then raise exception 'Active batch CR required' using errcode='42501'; end if;
  select * into target from classmate.profiles where id=target_profile and role='student'
    and verification_status='active' and batch_id=caller.batch_id for update;
  if target.id is null then raise exception 'Target must be an active student in your batch'; end if;
  update classmate.profiles set is_cr=false,cr_batch_id=null,cr_valid_until=null,updated_at=now()
    where id=caller.id;
  update classmate.profiles set is_cr=true,cr_batch_id=caller.batch_id,cr_valid_until=null,updated_at=now()
    where id=target.id returning * into target;
  insert into classmate.audit_log(actor_id,action,target_id)
    values(caller.id,'transfer_batch_cr',target.id);
  return target;
end;
$$;

create function classmate.set_batch_cr(target_profile uuid, target_active boolean default true)
returns classmate.profiles language plpgsql security definer set search_path = '' as $$
declare caller classmate.profiles%rowtype; target classmate.profiles%rowtype;
begin
  select * into caller from classmate.profiles where id=auth.uid() and verification_status='active';
  select * into target from classmate.profiles where id=target_profile and role='student'
    and verification_status='active' for update;
  if caller.id is null or target.id is null then raise exception 'Active caller and student required'; end if;
  if caller.role <> 'admin' and not (caller.role='student' and caller.is_cr
    and caller.batch_id=caller.cr_batch_id and caller.cr_batch_id=target.batch_id
    and (caller.cr_valid_until is null or caller.cr_valid_until>now())) then
    raise exception 'Batch CR or admin permission required' using errcode='42501';
  end if;
  if caller.role <> 'admin' and not classmate.is_active() then
    raise exception 'Caller account is inactive' using errcode='42501';
  end if;
  update classmate.profiles set is_cr=target_active,
    cr_batch_id=case when target_active then target.batch_id else null end,
    cr_valid_until=null,updated_at=now() where id=target.id returning * into target;
  insert into classmate.audit_log(actor_id,action,target_id)
    values(auth.uid(),case when target_active then 'appoint_batch_cr' else 'remove_batch_cr' end,target.id);
  return target;
end;
$$;

revoke all on function classmate.active_student_ban(),classmate.manage_student_ban(uuid,integer,text,boolean),
  classmate.transfer_batch_cr(uuid),classmate.set_batch_cr(uuid,boolean),
  classmate.student_ban_notification_job_context(uuid,text,uuid) from public,anon,authenticated;
revoke all on function classmate.notification_recipients(uuid),classmate.notification_device_allowed(uuid,uuid,text),
  classmate.web_notification_job_context(uuid,text,uuid) from public,anon,authenticated;
grant execute on function classmate.active_student_ban(),classmate.manage_student_ban(uuid,integer,text,boolean),
  classmate.transfer_batch_cr(uuid),classmate.set_batch_cr(uuid,boolean) to authenticated;
grant execute on function classmate.notification_recipients(uuid),classmate.notification_device_allowed(uuid,uuid,text),
  classmate.web_notification_job_context(uuid,text,uuid) to service_role;
notify pgrst,'reload schema';
commit;
