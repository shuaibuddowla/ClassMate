begin;

-- Upgrade manage_student_ban to support:
-- 1. Custom days input (any positive number of days)
-- 2. Permanent bans (target_days = -1 or >= 36500)
-- 3. Both CR and Admin support for custom & permanent durations
drop function if exists classmate.manage_student_ban(uuid,integer,text,boolean);

create or replace function classmate.manage_student_ban(
  target_profile uuid, target_days integer, target_reason text, target_lift boolean default false
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  caller classmate.profiles%rowtype;
  target classmate.profiles%rowtype;
  ban_id uuid;
  ban_interval interval;
  computed_ends_at timestamptz;
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

  if length(trim(coalesce(target_reason,''))) not between 3 and 500 then
    raise exception 'Please provide a valid reason (between 3 and 500 characters)';
  end if;

  -- Determine ban interval: -1 or >= 36500 indicates permanent (100 years). Otherwise positive integer days.
  if target_days = -1 or target_days >= 36500 then
    ban_interval := interval '100 years';
  elsif target_days >= 1 then
    ban_interval := make_interval(days => target_days);
  else
    raise exception 'Invalid ban duration specified';
  end if;

  computed_ends_at := now() + ban_interval;

  update classmate.student_bans set lifted_at=now(),lifted_by=auth.uid()
    where profile_id=target_profile and lifted_at is null and ends_at>now();
  insert into classmate.student_bans(profile_id,batch_id,banned_by,reason,ends_at)
    values(target_profile,target.batch_id,auth.uid(),trim(target_reason),computed_ends_at)
    returning id into ban_id;
  insert into classmate.notification_outbox(kind,record_id,department_id,batch_id,routing_label,recipient_profile_id)
    values('student_ban',ban_id,target.department_id,target.batch_id,classmate.routing_label(target.batch_id),target_profile);
  insert into classmate.audit_log(actor_id,action,target_id)
    values(auth.uid(),'ban_student',target_profile);
  return jsonb_build_object('id',ban_id,'ends_at',computed_ends_at,'is_permanent',(target_days = -1 or target_days >= 36500));
end;
$$;

revoke all on function classmate.manage_student_ban(uuid,integer,text,boolean) from public,anon;
grant execute on function classmate.manage_student_ban(uuid,integer,text,boolean) to authenticated;

-- Update notification body for permanent vs temporary bans
create or replace function classmate.student_ban_notification_job_context(target_event uuid,target_hash text,target_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare j classmate.notification_jobs%rowtype; e classmate.notification_outbox%rowtype; ban classmate.student_bans%rowtype; is_perm boolean;
begin
  select * into j from classmate.notification_jobs where event_id=target_event and token_hash=target_hash
    and lease_id=target_lease and state='leased' and lease_until>now();
  if not found or not classmate.notification_device_allowed(target_event,j.profile_id,target_hash) then return null; end if;
  select * into e from classmate.notification_outbox where id=target_event and kind in ('student_ban','student_ban_lifted');
  if not found then return null; end if;
  select * into ban from classmate.student_bans where id=e.record_id;
  is_perm := ban.ends_at > now() + interval '50 years';
  return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
    'batch_id',e.batch_id,
    'title',case when e.kind='student_ban_lifted' then 'ClassMate ban lifted' else (case when is_perm then 'ClassMate permanent restriction' else 'ClassMate temporary ban' end) end,
    'body',case
      when e.kind='student_ban_lifted' then 'Your ClassMate restriction has been lifted.'
      when is_perm then 'Your ClassMate account has been restricted permanently by your batch administration. Contact your CR or administrator.'
      else 'Access is restricted until '||to_char(ban.ends_at at time zone 'Asia/Dhaka','DD Mon YYYY HH12:MI AM')||'. Contact your CR or administrator.'
    end,
    'failures',j.failures);
end;
$$;

revoke all on function classmate.student_ban_notification_job_context(uuid,text,uuid) from public,anon;
grant execute on function classmate.student_ban_notification_job_context(uuid,text,uuid) to authenticated;

commit;
