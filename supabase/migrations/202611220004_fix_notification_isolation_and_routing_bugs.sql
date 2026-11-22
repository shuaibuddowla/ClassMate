-- ============================================================================
-- Migration: 202611220004_fix_notification_isolation_and_routing_bugs.sql
-- Description:
--   1. Fixes classmate.notification_recipients:
--      - Restores blood_request dispatch (was omitted in 202611090003, causing 0 recipients).
--      - Enforces strict batch isolation on targeted personal events (e.batch_id = p.batch_id).
--      - Preserves multi-teacher and student/admin batch delivery.
--   2. Fixes classmate.notification_device_allowed and academic_notification_device_allowed:
--      - Allows admins with a batch_id to receive academic batch notifications (previously blocked as role='admin').
--      - Allows teachers assigned via classmate.course_teachers (multi-teacher junction).
--      - Allows blood_request device verification.
--   3. Fixes classmate.academic_notification_job_context:
--      - Adds version_code and version_name for app_update releases so Android client doesn't discard them.
-- ============================================================================

begin;

-- 1. Fix notification_recipients
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

  -- Case A: App update announcement -> Deliver to all active registered users across all universities!
  if e.kind = 'app_update' then
    return query
      select distinct p.id, dt.fcm_token
      from classmate.profiles p
      join classmate.device_tokens dt on dt.profile_id = p.id
      where p.verification_status = 'active';
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
        -- Course-specific event: notify teachers assigned to that course via course_teachers or assignments
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
        -- General batch notice: notify teachers of the same department
        or (e.semester_course_id is null and p.department_id = b.department_id)
      ))
    );
end;
$$;

revoke all on function classmate.notification_recipients(uuid) from public, anon;
grant execute on function classmate.notification_recipients(uuid) to service_role;


-- 2. Fix academic_notification_device_allowed
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
    left join classmate.batches b on b.id = e.batch_id and b.department_id = e.department_id and b.is_active
    where e.id = target_event and (
      (e.kind = 'app_update' and (
        (p.role in ('student', 'admin') and exists (select 1 from classmate.batches own_batch where own_batch.id = p.batch_id and own_batch.is_active))
        or (p.role = 'teacher' and exists (select 1 from classmate.teacher_allowlist t where t.email = p.email and t.active))
        or (p.role = 'admin' and exists (select 1 from classmate.app_owners o where o.email = p.email and o.active))
      ))
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


-- 3. Fix notification_device_allowed
create or replace function classmate.notification_device_allowed(
  target_event uuid,
  target_profile uuid,
  target_hash text
)
returns boolean
language plpgsql
stable security definer
set search_path = ''
as $$
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
    ) and (e.batch_id is null or exists (
      select 1 from classmate.profiles p where p.id = target_profile and p.batch_id = e.batch_id
    ));
  end if;

  return classmate.academic_notification_device_allowed(target_event, target_profile, target_hash);
end;
$$;

revoke all on function classmate.notification_device_allowed(uuid, uuid, text) from public, anon;
grant execute on function classmate.notification_device_allowed(uuid, uuid, text) to authenticated, service_role;


-- 4. Fix academic_notification_job_context to include version_code and version_name for app_update
create or replace function classmate.academic_notification_job_context(
  target_event uuid,
  target_hash text,
  target_lease uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  content jsonb;
  sess record;
  mark_info record;
  notice_res record;
  rem_rec record;
  type_display text;
  course_display text;
  time_display text;
  room_display text;
  notif_title text;
  notif_body text;
begin
  select * into j from classmate.notification_jobs
  where event_id = target_event and token_hash = target_hash and lease_id = target_lease
    and state = 'leased' and lease_until > now();
  if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
    return null;
  end if;

  select * into e from classmate.notification_outbox where id = target_event;

  -- 1. Attendance Marked Confirmation
  if e.kind = 'attendance_marked' then
    select ar.id, ar.student_id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into mark_info
    from classmate.attendance_records ar
    join classmate.attendance_sessions s on s.id = ar.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where ar.id = e.record_id;

    if not found then return null; end if;

    if mark_info.student_id is distinct from j.profile_id then
      return null;
    end if;
    if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
      return null;
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
      'recipient_id', j.profile_id,
      'title', 'Attendance Recorded',
      'body', 'Your attendance for ' || mark_info.course_title || ' was marked present in the app.',
      'kind', 'attendance_marked',
      'record_id', mark_info.id::text,
      'failures', j.failures
    );
  end if;

  -- 2. Attendance Open Announcement
  if e.kind = 'attendance_open' then
    select s.id as session_id, s.routine_slot_id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into sess
    from classmate.attendance_sessions s
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where s.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
      'title', case when sess.course_code <> '' then 'Attendance Live · ' || sess.course_code else 'Attendance Live' end,
      'body', 'Punch your cards for ' || coalesce(sess.course_title, 'Class') || ' attendance.',
      'kind', 'attendance_open',
      'session_id', sess.session_id::text,
      'routine_slot_id', coalesce(sess.routine_slot_id::text, ''),
      'course_code', coalesce(sess.course_code, ''),
      'course_title', coalesce(sess.course_title, 'Class'),
      'route', 'my_attendance',
      'failures', j.failures
    );
  end if;

  -- 3. Dedicated Assessment Deadline Reminders (24h & 2h before CT / Assignment / Quiz / etc.)
  if e.kind = 'assessment_reminder' then
    select 
      ar.id as reminder_id,
      ar.reminder_type,
      ar.batch_id,
      a.id as assessment_id,
      a.title as assessment_title,
      a.type as assessment_type,
      a.course_code,
      a.course_title,
      a.deadline_date,
      a.deadline_time,
      a.room,
      a.syllabus
    into rem_rec
    from classmate.assessment_reminders ar
    join classmate.assessments a on a.id = ar.assessment_id
    where ar.id = e.record_id;

    if not found then return null; end if;

    -- Strict batch isolation: device token must belong to a student in this exact batch
    if (select batch_id from classmate.profiles where id = j.profile_id) is distinct from rem_rec.batch_id then
      return null;
    end if;

    type_display := case rem_rec.assessment_type
      when 'class_test' then 'Class Test'
      when 'assignment' then 'Assignment'
      when 'quiz' then 'Quiz'
      when 'lab_report' then 'Lab Report'
      when 'presentation' then 'Presentation'
      else coalesce(nullif(rem_rec.assessment_type, ''), 'Assessment')
    end;

    course_display := case
      when rem_rec.course_code <> '' and rem_rec.course_title <> '' then rem_rec.course_code || ' - ' || rem_rec.course_title
      when rem_rec.course_code <> '' then rem_rec.course_code
      when rem_rec.course_title <> '' then rem_rec.course_title
      else 'Class'
    end;

    time_display := case
      when rem_rec.deadline_time is not null and rem_rec.deadline_time <> '' then ' at ' || rem_rec.deadline_time
      else ''
    end;

    room_display := case
      when rem_rec.room is not null and rem_rec.room <> '' then ' in Room ' || rem_rec.room
      else ''
    end;

    if rem_rec.reminder_type = '24h' then
      notif_title := 'Reminder: ' || type_display || ' Tomorrow';
      notif_body := course_display || ' tomorrow' || time_display || room_display || '. Tap to view details and syllabus.';
    else
      notif_title := 'Upcoming: ' || type_display || ' in 2 Hours!';
      notif_body := course_display || ' today' || time_display || room_display || '. Review your preparation now.';
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', rem_rec.batch_id,
      'recipient_id', j.profile_id,
      'title', notif_title,
      'body', notif_body,
      'kind', 'assessment_reminder',
      'record_id', rem_rec.assessment_id,
      'assessment_id', rem_rec.assessment_id,
      'reminder_type', rem_rec.reminder_type,
      'failures', j.failures
    );
  end if;

  -- 4. Dedicated Resource Notice (File upload or Google Drive link)
  if e.kind = 'notice' then
    select n.*, f.title as file_title,
           coalesce(sc.title_override, c.course_title) as course_title,
           coalesce(sc.code_override, c.course_code) as course_code
    into notice_res
    from classmate.notices n
    left join classmate.file_metadata f on f.id = n.resource_id
    left join classmate.semester_courses sc on sc.id = coalesce(n.semester_course_id, f.semester_course_id)
    left join classmate.courses c on c.id = sc.course_id
    where n.id = e.record_id and not n.silent;

    if not found then return null; end if;

    if notice_res.resource_id is not null and notice_res.file_title is not null then
      return jsonb_build_object(
        'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
        'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
        'title', 'New Resources',
        'body', case
                  when notice_res.course_title is not null and notice_res.course_code is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title || ' (' || notice_res.course_code || ')'
                  when notice_res.course_title is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title
                  else
                    notice_res.file_title || ' is now available'
                end,
        'kind', 'notice',
        'record_id', notice_res.id::text,
        'resource_id', notice_res.resource_id::text,
        'failures', j.failures
      );
    end if;

    content := to_jsonb(notice_res);
  else
    case e.kind
      when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id = e.record_id and status = 'active';
      when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id = e.record_id;
      when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id = e.record_id;
      when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id = e.record_id;
      else return null;
    end case;
  end if;

  if content is null or (e.kind <> 'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind <> 'semester' and content->>'department_id' is distinct from e.department_id::text))) then
    return null;
  end if;

  return jsonb_build_object(
    'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
    'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
    'title', left(coalesce(content->>'title', 'ClassMate update'), 200),
    'body', left(coalesce(content->>'body', 'New class update available'), 240),
    'version_code', content->>'version_code',
    'version_name', content->>'version_name',
    'failures', j.failures,
    'kind', e.kind,
    'record_id', e.record_id::text,
    'event_id', e.id::text
  );
end;
$$;

revoke all on function classmate.academic_notification_job_context(uuid, text, uuid) from public, anon;
grant execute on function classmate.academic_notification_job_context(uuid, text, uuid) to service_role;

notify pgrst, 'reload schema';

commit;
