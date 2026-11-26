-- Migration: 202611250003_notice_edit_delete_notifications.sql
-- Description:
--   1. Emit 'notice_updated' outbox events when classmate.edit_notice updates a non-silent notice.
--   2. Emit 'notice_deleted' outbox events when classmate.delete_notice deletes a non-silent notice.
--   3. Support 'notice_updated' and 'notice_deleted' in classmate.academic_notification_job_context
--      so Android devices update active notifications in place or cancel them from the shade.

BEGIN;

-- 1. Update classmate.edit_notice to enqueue a fresh 'notice_updated' event
CREATE OR REPLACE FUNCTION classmate.edit_notice(target_id uuid, target_title text, target_body text)
 RETURNS classmate.notices
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  notice_row classmate.notices%rowtype;
  old_title text;
  old_body text;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      join classmate.batches b on b.id = notice_row.batch_id
      join classmate.departments d on d.id = b.department_id
      where p.id = auth.uid()
        and p.role = 'admin'
        and (p.institution_id is null or p.institution_id = d.institution_id)
    )
    or (notice_row.author_id = auth.uid() and classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))
  ) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;

  old_title := notice_row.title;
  old_body := notice_row.body;

  update classmate.notices
  set title = trim(target_title),
      body = coalesce(target_body, '')
  where id = target_id
  returning * into notice_row;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'edit_notice', target_id);

  if not notice_row.silent and (notice_row.title is distinct from old_title or notice_row.body is distinct from old_body) then
    delete from classmate.notification_outbox
    where kind = 'notice_updated' and record_id = target_id;

    insert into classmate.notification_outbox(
      kind, record_id, department_id, batch_id, semester_course_id, routing_label
    ) values (
      'notice_updated',
      notice_row.id,
      notice_row.department_id,
      notice_row.batch_id,
      notice_row.semester_course_id,
      classmate.routing_label(notice_row.batch_id, notice_row.semester_course_id)
    );
  end if;

  return notice_row;
end;
$function$;

-- 2. Update classmate.delete_notice to enqueue 'notice_deleted' before removing the notice
CREATE OR REPLACE FUNCTION classmate.delete_notice(target_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      join classmate.batches b on b.id = notice_row.batch_id
      join classmate.departments d on d.id = b.department_id
      where p.id = auth.uid()
        and p.role = 'admin'
        and (p.institution_id is null or p.institution_id = d.institution_id)
    )
    or (notice_row.author_id = auth.uid() and classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))
  ) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;

  delete from classmate.notification_outbox
  where kind in ('notice', 'notice_updated', 'notice_deleted') and record_id = target_id;

  delete from classmate.notices where id = target_id;

  if notice_row.class_change_id is not null then
    delete from classmate.notification_outbox
    where kind = 'class_change' and record_id = notice_row.class_change_id;
    delete from classmate.class_changes where id = notice_row.class_change_id;
  end if;

  if not notice_row.silent then
    insert into classmate.notification_outbox(
      kind, record_id, department_id, batch_id, semester_course_id, routing_label
    ) values (
      'notice_deleted',
      notice_row.id,
      notice_row.department_id,
      notice_row.batch_id,
      notice_row.semester_course_id,
      classmate.routing_label(notice_row.batch_id, notice_row.semester_course_id)
    );
  end if;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'delete_notice', target_id);
end;
$function$;

-- 3. Update classmate.academic_notification_job_context to handle 'notice_updated' and 'notice_deleted'
CREATE OR REPLACE FUNCTION classmate.academic_notification_job_context(target_event uuid, target_hash text, target_lease uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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

  -- 0. Notice Deleted -> silent cancel payload for client NotificationManager
  if e.kind = 'notice_deleted' then
    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
      'title', '',
      'body', '',
      'kind', 'notice_deleted',
      'record_id', e.record_id::text,
      'event_id', e.id::text,
      'failures', j.failures
    );
  end if;

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

  -- 4. Notice & Notice Updated (including Resource Notice)
  if e.kind in ('notice', 'notice_updated') then
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
        'kind', e.kind,
        'record_id', notice_res.id::text,
        'resource_id', notice_res.resource_id::text,
        'event_id', e.id::text,
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
$function$;

COMMIT;
