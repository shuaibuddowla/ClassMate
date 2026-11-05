begin;

-- 1. Fix notification_recipients: When recipient_profile_id is set (e.g. student_ban),
-- ONLY return that single targeted recipient. NEVER fall through to batch-wide broadcast!
create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language plpgsql stable security definer set search_path = '' as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return; end if;

  -- Targeted personal event (student ban, ban lifted, direct alert)
  if e.recipient_profile_id is not null then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id and p.verification_status = 'active';
    return;
  end if;

  -- Batch / academic event broadcast
  return query
    select distinct p.id, dt.fcm_token
    from classmate.profiles p
    join classmate.device_tokens dt on dt.profile_id = p.id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where (
      (p.role = 'student' and p.batch_id = b.id and p.department_id = b.department_id and p.verification_status = 'active')
      or (p.role = 'teacher' and p.verification_status = 'active' and exists (
        select 1 from classmate.teacher_course_assignments a
        join classmate.semester_courses sc on sc.id = a.semester_course_id
        join classmate.semesters s on s.id = sc.semester_id
        join classmate.teacher_allowlist t on t.email = p.email and t.department_id = b.department_id and t.active
        where a.teacher_id = p.id and a.active and s.batch_id = b.id and s.status = 'active'
          and (e.semester_course_id is null or sc.id = e.semester_course_id)
      ))
    );
end;
$$;

-- 2. Fix notification_device_allowed: Strictly enforce target matching for individual events
create or replace function classmate.notification_device_allowed(target_event uuid, target_profile uuid, target_hash text)
returns boolean language plpgsql stable security definer set search_path = '' as $$
declare
  recip uuid;
begin
  select recipient_profile_id into recip from classmate.notification_outbox where id = target_event;
  if recip is not null then
    return recip = target_profile and exists (
      select 1 from classmate.device_tokens d
      where d.token_hash = target_hash and d.profile_id = target_profile
    );
  end if;

  return classmate.academic_notification_device_allowed(target_event, target_profile, target_hash);
end;
$$;

-- 3. Fix student_ban_notification_job_context to include recipient_id and drop cross-user jobs
create or replace function classmate.student_ban_notification_job_context(target_event uuid, target_hash text, target_lease uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  ban classmate.student_bans%rowtype;
begin
  select * into j from classmate.notification_jobs where event_id = target_event and token_hash = target_hash
    and lease_id = target_lease and state = 'leased' and lease_until > now();
  if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
    return null;
  end if;

  select * into e from classmate.notification_outbox where id = target_event and kind in ('student_ban', 'student_ban_lifted');
  if not found then return null; end if;

  -- Strict guard: never allow ban notification payload for a non-targeted user
  if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
    return null;
  end if;

  select * into ban from classmate.student_bans where id = e.record_id;

  return jsonb_build_object(
    'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
    'batch_id', e.batch_id,
    'recipient_id', j.profile_id,
    'kind', e.kind,
    'title', case when e.kind = 'student_ban_lifted' then 'ClassMate ban lifted' else 'ClassMate temporary ban' end,
    'body', case when e.kind = 'student_ban_lifted' then 'Your temporary ClassMate ban has been lifted.' else
      'Access is restricted until ' || to_char(ban.ends_at at time zone 'Asia/Dhaka', 'DD Mon YYYY HH12:MI AM') || '. Contact your CR or administrator.' end,
    'failures', j.failures
  );
end;
$$;

-- 4. Cancel any remaining pending ban jobs that were accidentally created for non-targeted students
update classmate.notification_jobs j
set state = 'cancelled', last_error = 'dropped: broadcast leak fix'
from classmate.notification_outbox e
where j.event_id = e.id
  and e.kind in ('student_ban', 'student_ban_lifted')
  and e.recipient_profile_id is not null
  and j.profile_id <> e.recipient_profile_id
  and j.state in ('pending', 'leased');

notify pgrst, 'reload schema';
commit;
