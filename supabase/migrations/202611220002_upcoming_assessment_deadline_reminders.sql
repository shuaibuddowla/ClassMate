-- ============================================================================
-- Migration: 202611220002_upcoming_assessment_deadline_reminders.sql
-- Description:
--   1. Creates classmate.assessment_reminders table to track 24h and 2h reminders.
--   2. Strict batch_id and department_id isolation with row-level security.
--   3. Automatically computes deadline timestamp in Asia/Dhaka (BST).
--   4. Automatically schedules 24h & 2h reminders on assessment insert/update.
--   5. Provides classmate.process_due_assessment_reminders() to enqueue due reminders.
--   6. Enriches classmate.academic_notification_job_context to format reminder payloads.
--   7. Configures pg_cron to periodically process due assessment reminders.
-- ============================================================================

begin;

-- 1. Create classmate.assessment_reminders table
create table if not exists classmate.assessment_reminders (
  id uuid primary key default gen_random_uuid(),
  assessment_id text not null references classmate.assessments(id) on delete cascade,
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  reminder_type text not null check (reminder_type in ('24h', '2h')),
  scheduled_for timestamptz not null,
  dispatched_at timestamptz,
  created_at timestamptz not null default now(),
  unique (assessment_id, reminder_type)
);

create index if not exists idx_assessment_reminders_due 
  on classmate.assessment_reminders (scheduled_for) 
  where dispatched_at is null;

create index if not exists idx_assessment_reminders_batch 
  on classmate.assessment_reminders (batch_id);

alter table classmate.assessment_reminders enable row level security;
grant select on classmate.assessment_reminders to authenticated, service_role;

drop policy if exists assessment_reminders_read on classmate.assessment_reminders;
create policy assessment_reminders_read on classmate.assessment_reminders
  for select to authenticated
  using (classmate.can_read_batch(batch_id));

-- 2. Helper function to compute deadline timestamp in Asia/Dhaka
create or replace function classmate.assessment_deadline_timestamp(
  d_date date,
  d_time text
) returns timestamptz
language sql
immutable
set search_path = ''
as $$
  select case 
    when d_time ~* '^[0-1]?[0-9]:[0-5][0-9]\s*(AM|PM)$' then
      (d_date::text || ' ' || d_time)::timestamp at time zone 'Asia/Dhaka'
    when d_time ~* '^[0-2]?[0-9]:[0-5][0-9]$' then
      (d_date::text || ' ' || d_time)::timestamp at time zone 'Asia/Dhaka'
    else
      (d_date::text || ' 23:59:00')::timestamp at time zone 'Asia/Dhaka'
  end;
$$;

-- 3. Trigger function to auto-sync reminders on assessment changes
create or replace function classmate.sync_assessment_reminders()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  ts timestamptz;
begin
  ts := classmate.assessment_deadline_timestamp(new.deadline_date, new.deadline_time);

  if ts > now() then
    -- Schedule 24h reminder (if deadline is at least 12 hours away)
    if ts > now() + interval '12 hours' then
      insert into classmate.assessment_reminders (
        assessment_id,
        batch_id,
        reminder_type,
        scheduled_for
      ) values (
        new.id,
        new.batch_id,
        '24h',
        ts - interval '24 hours'
      )
      on conflict (assessment_id, reminder_type) do update
        set batch_id = excluded.batch_id,
            scheduled_for = excluded.scheduled_for,
            dispatched_at = case when excluded.scheduled_for > now() then null else classmate.assessment_reminders.dispatched_at end;
    end if;

    -- Schedule 2h reminder
    insert into classmate.assessment_reminders (
      assessment_id,
      batch_id,
      reminder_type,
      scheduled_for
    ) values (
      new.id,
      new.batch_id,
      '2h',
      ts - interval '2 hours'
    )
    on conflict (assessment_id, reminder_type) do update
      set batch_id = excluded.batch_id,
          scheduled_for = excluded.scheduled_for,
          dispatched_at = case when excluded.scheduled_for > now() then null else classmate.assessment_reminders.dispatched_at end;
  end if;

  return new;
end;
$$;

drop trigger if exists trigger_sync_assessment_reminders on classmate.assessments;
create trigger trigger_sync_assessment_reminders
after insert or update of deadline_date, deadline_time, batch_id
on classmate.assessments
for each row
execute function classmate.sync_assessment_reminders();

-- 4. Initial seed for existing future assessments
insert into classmate.assessment_reminders (
  assessment_id,
  batch_id,
  reminder_type,
  scheduled_for
)
select 
  a.id,
  a.batch_id,
  '24h',
  classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) - interval '24 hours'
from classmate.assessments a
where classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) > now() + interval '12 hours'
on conflict (assessment_id, reminder_type) do nothing;

insert into classmate.assessment_reminders (
  assessment_id,
  batch_id,
  reminder_type,
  scheduled_for
)
select 
  a.id,
  a.batch_id,
  '2h',
  classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) - interval '2 hours'
from classmate.assessments a
where classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) > now()
on conflict (assessment_id, reminder_type) do nothing;

-- 5. Enqueue due assessment reminders into notification_outbox
create or replace function classmate.process_due_assessment_reminders()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  rem record;
  processed_count integer := 0;
begin
  for rem in
    select 
      ar.id as reminder_id,
      ar.assessment_id,
      ar.batch_id,
      ar.reminder_type,
      b.department_id,
      classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) as deadline_ts
    from classmate.assessment_reminders ar
    join classmate.assessments a on a.id = ar.assessment_id
    join classmate.batches b on b.id = ar.batch_id
    where ar.dispatched_at is null
      and ar.scheduled_for <= now()
      and (
        (ar.reminder_type = '24h' and classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) > now() + interval '6 hours')
        or
        (ar.reminder_type = '2h' and classmate.assessment_deadline_timestamp(a.deadline_date, a.deadline_time) > now())
      )
    order by ar.scheduled_for asc
    for update of ar skip locked
    limit 20
  loop
    update classmate.assessment_reminders
    set dispatched_at = now()
    where id = rem.reminder_id;

    insert into classmate.notification_outbox (
      kind,
      record_id,
      batch_id,
      department_id,
      routing_label
    ) values (
      'assessment_reminder',
      rem.reminder_id,
      rem.batch_id,
      rem.department_id,
      'batch-students'
    )
    on conflict (kind, record_id) do nothing;

    processed_count := processed_count + 1;
  end loop;

  if processed_count > 0 then
    perform classmate.request_notification_dispatch();
  end if;

  return processed_count;
end;
$$;

-- 6. Hook process_due_assessment_reminders into prepare_notification_jobs
create or replace function classmate.prepare_notification_jobs(event_limit integer default 5)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  e classmate.notification_outbox%rowtype;
  prepared integer := 0;
begin
  -- First process any due assessment reminders so they can be prepared in this round
  perform classmate.process_due_assessment_reminders();

  for e in
    select * from classmate.notification_outbox
    where delivered_at is null and not jobs_prepared
    order by (kind = 'app_update'), created_at
    for update skip locked
    limit least(greatest(event_limit, 1), 20)
  loop
    insert into classmate.notification_jobs (event_id, profile_id, token_hash, state)
      select e.id, r.profile_id, encode(extensions.digest(r.fcm_token, 'sha256'), 'hex'),
        case when d.outbox_id is not null then 'accepted' else 'pending' end
      from classmate.notification_recipients(e.id) r
      left join classmate.notification_deliveries d on d.outbox_id = e.id and d.token_hash = encode(extensions.digest(r.fcm_token, 'sha256'), 'hex')
      on conflict do nothing;

    update classmate.notification_outbox
    set jobs_prepared = true, processing_until = null, last_error = null
    where id = e.id;

    prepared := prepared + 1;
  end loop;

  return prepared;
end;
$$;

-- 7. Update academic_notification_job_context to generate assessment_reminder push payloads
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
    'failures', j.failures,
    'kind', e.kind,
    'record_id', e.record_id::text,
    'event_id', e.id::text
  );
end;
$$;

-- 8. Setup pg_cron schedule for assessment reminders (every 5 minutes)
do $$
begin
  if exists (select 1 from cron.job where jobname = 'classmate-assessment-reminders') then
    perform cron.unschedule('classmate-assessment-reminders');
  end if;
end $$;

select cron.schedule(
  'classmate-assessment-reminders',
  '*/5 * * * *',
  'select classmate.process_due_assessment_reminders();'
);

commit;
