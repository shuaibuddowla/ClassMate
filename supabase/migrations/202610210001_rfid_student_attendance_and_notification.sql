begin;

-- ============================================================================
-- 1. Update Attendance Notification Payload for Physical RFID Station
-- ============================================================================
create or replace function classmate.notification_device_dispatch_batch(batch_size int default 50)
returns table (job_id uuid, payload jsonb)
language plpgsql security definer set search_path='' as $$
declare
  locked_jobs uuid[];
begin
  select array_agg(id) into locked_jobs from (
    select id from classmate.notification_device_jobs
    where state in ('pending','retry')
      and (not delivered or delivered is null)
      and (next_attempt_at is null or next_attempt_at <= now())
    order by created_at asc limit batch_size for update skip locked
  ) sub;
  if locked_jobs is null or array_length(locked_jobs, 1) is null then return; end if;
  update classmate.notification_device_jobs set state='in_flight', attempts=attempts+1, last_attempt_at=now()
  where id = any(locked_jobs);
  return query select j.id as job_id, (case when j.profile_id is null or j.token_hash is null then null
    else classmate.notification_device_payload(j.id) end) as payload
  from classmate.notification_device_jobs j where j.id = any(locked_jobs);
end $$;

create or replace function classmate.notification_device_payload(target_job uuid)
returns jsonb language plpgsql stable security definer set search_path='' as $$
declare
  j record;
  e record;
  content jsonb;
  sess record;
begin
  select job.id, job.event_id, job.profile_id, job.token_hash, job.failures
  into j from classmate.notification_device_jobs job where job.id=target_job;
  if not found then return null; end if;
  select * into e from classmate.notification_outbox where id=j.event_id;
  if not found then return null; end if;

  -- Specialized Attendance Notification Payload (RFID Station Workflow)
  if e.kind = 'attendance_open' then
    select s.id as session_id, s.routine_slot_id, coalesce(sc.code_override, c.course_code) as course_code,
           coalesce(sc.title_override, c.course_title) as course_title
    into sess
    from classmate.attendance_sessions s
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where s.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id=j.profile_id)),
      'title', 'Attendance Live · ' || coalesce(sess.course_code, 'Course'),
      'body', 'Attendance station is active for ' || coalesce(sess.course_title, 'Class') || '. Tap your student card on the RFID reader.',
      'kind', 'attendance_open',
      'session_id', sess.session_id::text,
      'course_code', coalesce(sess.course_code, ''),
      'course_title', coalesce(sess.course_title, 'Class'),
      'route', 'my_attendance',
      'failures', j.failures
    );
  end if;

  case e.kind
    when 'notice' then select to_jsonb(n) into content from classmate.notices n where id=e.record_id and not silent;
    when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id=e.record_id and status='active';
    when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id=e.record_id;
    when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id=e.record_id;
    when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id=e.record_id;
    else return null;
  end case;
  if content is null or (e.kind<>'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind<>'semester' and content->>'department_id' is distinct from e.department_id::text))) then return null; end if;
  return jsonb_build_object('fcm_token',(select fcm_token from classmate.device_tokens where token_hash=j.token_hash and profile_id=j.profile_id),
    'batch_id',coalesce(e.batch_id,(select batch_id from classmate.profiles where id=j.profile_id)),
    'title',left(coalesce(content->>'title','ClassMate update'),200),'body',left(coalesce(content->>'body','New class update available'),240),
    'failures',j.failures,'version_code',content->>'version_code','version_name',content->>'version_name');
end $$;

-- ============================================================================
-- 2. Enhanced Student "My Attendance" RPC
-- ============================================================================
create or replace function classmate.student_my_attendance(
  target_batch uuid default null
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid := target_batch;
  courses_list jsonb;
  total_held int := 0;
  total_attended int := 0;
begin
  if caller_id is null and current_user in ('postgres', 'supabase_admin') then
    select id into caller_id from classmate.profiles where role = 'admin' limit 1;
  end if;

  if not (current_user in ('postgres', 'supabase_admin') or classmate.is_active()) then
    raise exception 'Active profile required' using errcode='42501';
  end if;

  if v_batch is null then
    select batch_id into v_batch from classmate.profiles where id = caller_id;
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'semester_course_id', sc.id,
        'course_code', coalesce(sc.code_override, c.course_code),
        'course_title', coalesce(sc.title_override, c.course_title),
        'course_type', coalesce(sc.type_override, c.course_type),
        'credit', coalesce(sc.credit_override, c.credit, 3.0),
        'teacher_name', coalesce(td.full_name, 'Instructor'),
        'attendance_enabled', true,
        'classes_held', coalesce(c_stat.held, 0),
        'classes_attended', coalesce(c_stat.attended, 0),
        'percentage', case 
          when coalesce(c_stat.held, 0) > 0 
          then round((coalesce(c_stat.attended, 0)::numeric / c_stat.held::numeric) * 100, 1)
          else 0 end,
        'history', coalesce(c_stat.history, '[]'::jsonb)
      ) order by coalesce(sc.code_override, c.course_code) asc
    ),
    '[]'::jsonb
  )
  into courses_list
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters s on s.id = sc.semester_id
  left join classmate.teacher_directory td on td.id = sc.teacher_record_id
  left join lateral (
    select
      count(distinct sess.id) as held,
      count(distinct r.session_id) as attended,
      coalesce(
        jsonb_agg(
          jsonb_build_object(
            'session_id', sess.id,
            'date', (sess.started_at at time zone 'Asia/Dhaka')::date,
            'time', to_char((sess.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
            'room', coalesce(rs.room, 'Station Reader'),
            'status', case when r.id is not null then 'present' else 'absent' end,
            'method', coalesce(r.method, 'rfid'),
            'marked_at', r.marked_at
          ) order by sess.started_at desc
        ) filter (where sess.id is not null),
        '[]'::jsonb
      ) as history
    from classmate.attendance_sessions sess
    left join classmate.routine_slots rs on rs.id = sess.routine_slot_id
    left join classmate.attendance_records r on r.session_id = sess.id and r.student_id = caller_id
    where coalesce(sess.semester_course_id, rs.semester_course_id) = sc.id
  ) c_stat on true
  where s.batch_id = v_batch and s.status in ('active', 'not_started')
    and coalesce(c_stat.held, 0) > 0;

  select coalesce(sum((item->>'classes_held')::int), 0),
         coalesce(sum((item->>'classes_attended')::int), 0)
  into total_held, total_attended
  from jsonb_array_elements(courses_list) as item;

  return jsonb_build_object(
    'courses', courses_list,
    'total_classes_held', total_held,
    'total_classes_attended', total_attended,
    'overall_percentage', case 
      when total_held > 0 
      then round((total_attended::numeric / total_held::numeric) * 100, 1)
      else 0 end
  );
end $$;

revoke all on function classmate.student_my_attendance(uuid) from public, anon;
grant execute on function classmate.student_my_attendance(uuid) to authenticated;

commit;
