begin;

-- Exclude banned students from all batch broadcasts and non-ban notifications.
-- A banned student must ONLY receive 'student_ban' and 'student_ban_lifted'.
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
    if e.kind in ('student_ban', 'student_ban_lifted') then
      return query
        select distinct p.id, dt.fcm_token
        from classmate.profiles p
        join classmate.device_tokens dt on dt.profile_id = p.id
        where p.id = e.recipient_profile_id and p.verification_status = 'active';
      return;
    end if;

    -- For any other targeted personal event, do NOT deliver if the student is currently banned
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.id = e.recipient_profile_id and p.verification_status = 'active'
        and not exists (
          select 1 from classmate.student_bans sb
          where sb.profile_id = p.id
            and sb.lifted_at is null
            and sb.starts_at <= now()
            and sb.ends_at > now()
        );
      return;
  end if;

  -- Batch / academic event broadcast (notices, routine changes, etc.)
  -- Strictly EXCLUDE any student who currently has an active ban!
  return query
    select distinct p.id, dt.fcm_token
    from classmate.profiles p
    join classmate.device_tokens dt on dt.profile_id = p.id
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where (
      (
        p.role = 'student'
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

-- Enforce device-level permission check:
-- If target_profile is currently banned, REJECT all notifications except student_ban and student_ban_lifted!
create or replace function classmate.notification_device_allowed(target_event uuid, target_profile uuid, target_hash text)
returns boolean language plpgsql stable security definer set search_path = '' as $$
declare
  e classmate.notification_outbox%rowtype;
  is_banned boolean;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if e.id is null then return false; end if;

  -- Check if target_profile has an active ban
  select exists (
    select 1 from classmate.student_bans sb
    where sb.profile_id = target_profile
      and sb.lifted_at is null
      and sb.starts_at <= now()
      and sb.ends_at > now()
  ) into is_banned;

  -- If the student has an active ban, they must ONLY receive ban and unban notifications
  if is_banned and e.kind not in ('student_ban', 'student_ban_lifted') then
    return false;
  end if;

  -- Targeted personal event
  if e.recipient_profile_id is not null then
    return e.recipient_profile_id = target_profile and exists (
      select 1 from classmate.device_tokens d
      where d.token_hash = target_hash and d.profile_id = target_profile
    );
  end if;

  return classmate.academic_notification_device_allowed(target_event, target_profile, target_hash);
end;
$$;

commit;
