begin;

-- Exclude teachers and devices already on the target (or newer) app version from app_update push notifications.
-- app_update events only use notification_outbox + FCM and never insert into classmate.notices.

create or replace function classmate.notification_recipients(target_event uuid)
returns table(profile_id uuid, fcm_token text)
language plpgsql
security definer
set search_path to ''
as $$
declare
  e classmate.notification_outbox%rowtype;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return; end if;

  -- Case A: App update announcement -> Deliver to active non-teacher users on previous app versions
  if e.kind = 'app_update' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      join classmate.app_releases ar on ar.id = e.record_id
      where p.verification_status = 'active'
        and p.role <> 'teacher'
        and (dt.app_version is null or dt.app_version < ar.version_code);
    return;
  end if;

  -- Case B: Targeted personal event (student ban, ban lifted, dues reminder, fund deposit, attendance confirmation)
  if e.recipient_profile_id is not null then
    if e.kind in ('student_ban', 'student_ban_lifted') then
      return query
        select distinct p.id, dt.fcm_token
        from classmate.profiles p
        join classmate.device_tokens dt on dt.profile_id = p.id
        where p.id = e.recipient_profile_id and p.verification_status = 'active';
      return;
    end if;

    -- For any other targeted personal event:
    -- 1. Exclude if student has an active ban
    -- 2. Strictly enforce batch isolation if event has a target batch
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id
        and p.verification_status = 'active'
        and (e.batch_id is null or p.batch_id = e.batch_id)
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        );
      return;
  end if;

  -- Case C: Blood Request Broadcast -> Targeted strictly to eligible donors matching criteria across campus
  if e.kind = 'blood_request' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.verification_status = 'active'
        and classmate.blood_broadcast_allowed(e.record_id, p.id);
    return;
  end if;

  -- Case D: Batch / academic event broadcast (notices, routine changes, class cancellations, resources, assessment reminders)
  -- Strictly ISOLATED to the specific batch & department!
  -- Strictly EXCLUDES any student who currently has an active ban!
  return query
    select distinct p.id, dt.fcm_token
    from classmate.profiles p
    join classmate.device_tokens dt on dt.profile_id = p.id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where (
      (
        p.role in ('student', 'admin')
        and p.batch_id = b.id
        and p.department_id = b.department_id
        and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        )
      )
      or (p.role = 'teacher' and p.verification_status = 'active' and (
        (e.semester_course_id is not null and (
          exists (
            select 1 from classmate.course_teachers ct
            join classmate.teacher_directory td on td.id = ct.teacher_record_id
            where ct.semester_course_id = e.semester_course_id
              and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
          )
          or exists (
            select 1 from classmate.teacher_course_assignments a
            where a.teacher_id = p.id and a.semester_course_id = e.semester_course_id and a.active
          )
        ))
        or (e.semester_course_id is null and p.department_id = b.department_id)
      ))
    );
end;
$$;

revoke all on function classmate.notification_recipients(uuid) from public, anon;
grant execute on function classmate.notification_recipients(uuid) to service_role;


create or replace function classmate.academic_notification_device_allowed(
  target_event uuid,
  target_profile uuid,
  target_hash text
) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  return exists(
    select 1 from classmate.notification_outbox e
    join classmate.profiles p on p.id = target_profile and p.verification_status = 'active'
    join classmate.device_tokens dt on dt.profile_id = p.id and dt.token_hash = target_hash
    left join classmate.app_releases ar on e.kind = 'app_update' and ar.id = e.record_id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where e.id = target_event and (
      (e.kind = 'app_update'
        and p.role <> 'teacher'
        and (dt.app_version is null or (ar.id is not null and dt.app_version < ar.version_code))
        and (
          (p.role in ('student', 'admin') and exists (select 1 from classmate.batches own_batch where own_batch.id = p.batch_id and own_batch.is_active))
          or (p.role = 'admin' and exists (select 1 from classmate.app_owners o where o.email = p.email and o.active))
        )
      )
      or (e.kind = 'blood_request' and (
        classmate.blood_broadcast_allowed(e.record_id, target_profile) or classmate.blood_donor_allowed(e.record_id, target_profile)
      ))
      or (e.kind not in ('app_update', 'blood_request') and b.id is not null and (
        (p.role in ('student', 'admin') and p.batch_id = b.id and p.department_id = b.department_id)
        or (p.role = 'teacher' and (
          (e.semester_course_id is not null and (
            exists (
              select 1 from classmate.course_teachers ct
              join classmate.teacher_directory td on td.id = ct.teacher_record_id
              where ct.semester_course_id = e.semester_course_id
                and (td.profile_id = p.id or lower(trim(td.email)) = lower(trim(p.email)))
            )
            or exists (
              select 1 from classmate.teacher_course_assignments a
              where a.teacher_id = p.id and a.semester_course_id = e.semester_course_id and a.active
            )
          ))
          or (e.semester_course_id is null and p.department_id = b.department_id)
        ))
      ))
    )
  );
end;
$$;

revoke all on function classmate.academic_notification_device_allowed(uuid, uuid, text) from public, anon;
grant execute on function classmate.academic_notification_device_allowed(uuid, uuid, text) to service_role;

notify pgrst, 'reload schema';

commit;
