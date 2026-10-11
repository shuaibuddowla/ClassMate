begin;

-- ============================================================================
-- 1. Ensure table grants for authenticated role
-- ============================================================================
grant select, insert, update on classmate.attendance_sessions to authenticated;
grant select, insert, update on classmate.attendance_records to authenticated;

-- ============================================================================
-- 2. Enhance mark_attendance: strict class period verification
-- ============================================================================
create or replace function classmate.mark_attendance(
  target_session uuid,
  qr_token text,
  time_window bigint
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess record;
  current_win bigint;
  expected_token text;
  caller_student_id uuid := auth.uid();
  rec_id uuid;
  is_late boolean := false;
  scan_time time;
begin
  select s.*, rs.start_time, rs.end_time, rs.semester_course_id
  into sess
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  where s.id = target_session;

  if not found then
    raise exception 'Attendance session not found' using errcode='42704';
  end if;

  if sess.status = 'closed' then
    raise exception 'Attendance session is already closed' using errcode='22023';
  end if;

  -- 15-second epoch window check
  current_win := extract(epoch from now())::bigint / 15;
  if abs(current_win - time_window) > 1 then
    is_late := true;
  end if;

  -- Verify HMAC hash
  expected_token := encode(extensions.hmac(time_window::text, sess.session_secret, 'sha256'), 'hex');
  if lower(trim(qr_token)) <> lower(trim(expected_token)) then
    raise exception 'Invalid or expired QR code' using errcode='22023';
  end if;

  -- Strict Class Period Verification:
  -- The scanned moment (time_window * 15) in Bangladesh Time (UTC+6) must be within
  -- [start_time - 15 minutes, end_time + 15 minutes] of the routine slot
  scan_time := ((to_timestamp(time_window * 15) at time zone 'Asia/Dhaka')::time);
  if sess.start_time is not null and sess.end_time is not null then
    if scan_time < (sess.start_time - interval '15 minutes') or scan_time > (sess.end_time + interval '15 minutes') then
      raise exception 'Scan timestamp is outside the class period' using errcode='22023';
    end if;
  end if;

  -- Verify student belongs to this batch
  if not exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.profiles p on p.id = caller_student_id
    where rs.id = sess.routine_slot_id and p.batch_id = s.batch_id and p.verification_status = 'active'
  ) and not classmate.is_owner() then
    raise exception 'You are not enrolled in this batch' using errcode='42501';
  end if;

  insert into classmate.attendance_records(
    session_id, student_id, marked_at, synced_at, method, confidence
  ) values (
    target_session, caller_student_id,
    to_timestamp(time_window * 15),
    now(),
    'qr',
    case when is_late then 'late' else 'normal' end
  )
  on conflict (session_id, student_id) do nothing
  returning id into rec_id;

  return jsonb_build_object(
    'success', true,
    'session_id', target_session,
    'status', case when is_late then 'late_recorded' else 'present' end
  );
end $$;

-- ============================================================================
-- 3. Teacher Dashboard Overview RPC
-- ============================================================================
create or replace function classmate.teacher_course_attendance_overview(
  target_batch uuid,
  target_course uuid default null
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  res jsonb;
begin
  select jsonb_agg(course_data)
  into res
  from (
    select
      sc.id as semester_course_id,
      c.course_code,
      c.course_title,
      c.course_type,
      count(distinct s.id) as total_sessions,
      (
        select count(*)
        from classmate.profiles p
        where p.batch_id = target_batch and p.role = 'student' and p.verification_status = 'active'
      ) as enrolled_students,
      coalesce(
        round(
          avg(
            case when sess_stat.tot > 0 then (sess_stat.pres::numeric / sess_stat.tot::numeric) * 100 else 0 end
          ), 1
        ), 0
      ) as average_percentage,
      coalesce(
        jsonb_agg(
          jsonb_build_object(
            'session_id', s.id,
            'started_at', s.started_at,
            'closed_at', s.closed_at,
            'status', s.status,
            'room', rs.room,
            'present_count', coalesce(sess_stat.pres, 0),
            'total_count', coalesce(sess_stat.tot, 0)
          ) order by s.started_at desc
        ) filter (where s.id is not null),
        '[]'::jsonb
      ) as sessions
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.routine_slots rs on rs.semester_course_id = sc.id
    left join classmate.attendance_sessions s on s.routine_slot_id = rs.id
    left join lateral (
      select
        count(r.id) as pres,
        (
          select count(*)
          from classmate.profiles p
          where p.batch_id = sem.batch_id and p.role = 'student' and p.verification_status = 'active'
        ) as tot
      from classmate.attendance_records r
      where r.session_id = s.id
    ) sess_stat on true
    where sem.batch_id = target_batch
      and (target_course is null or sc.id = target_course)
      and (
        is_admin
        or exists (
          select 1 from classmate.teacher_course_assignments tca
          where tca.semester_course_id = sc.id and tca.teacher_id = caller_id and tca.active
        )
      )
    group by sc.id, c.course_code, c.course_title, c.course_type, sem.batch_id
    order by c.course_code asc
  ) course_data;

  return coalesce(res, '[]'::jsonb);
end $$;

-- ============================================================================
-- 4. Long-Term Course Attendance Matrix RPC (for Sheet / Table view)
-- ============================================================================
create or replace function classmate.course_attendance_matrix(
  target_semester_course uuid
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  sessions_list jsonb;
  students_list jsonb;
begin
  -- Verify permission
  if not is_admin and not exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
  ) then
    raise exception 'Unauthorized to view attendance matrix for this course' using errcode='42501';
  end if;

  select s.batch_id, c.course_code, c.course_title
  into v_batch_id, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  -- 1. Get all sessions for this course
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'session_id', s.id,
        'started_at', s.started_at,
        'date', (s.started_at at time zone 'Asia/Dhaka')::date,
        'status', s.status,
        'room', rs.room
      ) order by s.started_at asc
    ),
    '[]'::jsonb
  )
  into sessions_list
  from classmate.routine_slots rs
  join classmate.attendance_sessions s on s.routine_slot_id = rs.id
  where rs.semester_course_id = target_semester_course;

  -- 2. Build student rows with attendance status map for every session
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'records', coalesce(stu_records.rec_map, '{}'::jsonb),
        'present_count', coalesce(stu_records.pres_cnt, 0),
        'total_sessions', jsonb_array_length(sessions_list),
        'percentage', case 
          when jsonb_array_length(sessions_list) > 0 
          then round((coalesce(stu_records.pres_cnt, 0)::numeric / jsonb_array_length(sessions_list)::numeric) * 100, 1)
          else 0 end
      ) order by coalesce(p.student_id, p.full_name) asc
    ),
    '[]'::jsonb
  )
  into students_list
  from classmate.profiles p
  left join lateral (
    select
      jsonb_object_agg(r.session_id, 'present') as rec_map,
      count(r.id) as pres_cnt
    from classmate.attendance_records r
    join classmate.attendance_sessions s on s.id = r.session_id
    join classmate.routine_slots rs on rs.id = s.routine_slot_id
    where r.student_id = p.id and rs.semester_course_id = target_semester_course
  ) stu_records on true
  where p.batch_id = v_batch_id and p.role = 'student' and p.verification_status = 'active';

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'sessions', sessions_list,
    'students', students_list
  );
end $$;

-- ============================================================================
-- 5. Student "My Attendance" RPC
-- ============================================================================
create or replace function classmate.student_my_attendance(
  target_batch uuid default null
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid;
  courses_list jsonb;
  total_held int := 0;
  total_attended int := 0;
begin
  select coalesce(target_batch, batch_id) into v_batch
  from classmate.profiles where id = caller_id;

  if v_batch is null then
    return jsonb_build_object('overall_percentage', 0, 'courses', '[]'::jsonb);
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'semester_course_id', sc.id,
        'course_code', c.course_code,
        'course_title', c.course_title,
        'course_type', c.course_type,
        'classes_held', coalesce(c_stat.held, 0),
        'classes_attended', coalesce(c_stat.attended, 0),
        'percentage', case 
          when coalesce(c_stat.held, 0) > 0 
          then round((coalesce(c_stat.attended, 0)::numeric / c_stat.held::numeric) * 100, 1)
          else 0 end,
        'recent_history', coalesce(c_stat.recent, '[]'::jsonb)
      ) order by c.course_code asc
    ),
    '[]'::jsonb
  )
  into courses_list
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  join classmate.semesters s on s.id = sc.semester_id
  left join lateral (
    select
      count(distinct sess.id) as held,
      count(distinct r.session_id) as attended,
      coalesce(
        jsonb_agg(
          jsonb_build_object(
            'session_id', sess.id,
            'date', (sess.started_at at time zone 'Asia/Dhaka')::date,
            'time', (sess.started_at at time zone 'Asia/Dhaka')::time,
            'status', case when r.id is not null then 'present' else 'absent' end
          ) order by sess.started_at desc
        ) filter (where sess.id is not null),
        '[]'::jsonb
      ) as recent
    from classmate.routine_slots rs
    join classmate.attendance_sessions sess on sess.routine_slot_id = rs.id
    left join classmate.attendance_records r on r.session_id = sess.id and r.student_id = caller_id
    where rs.semester_course_id = sc.id
  ) c_stat on true
  where s.batch_id = v_batch and s.status in ('active', 'not_started');

  -- Calculate overall metrics
  select
    coalesce(sum((c->>'classes_held')::int), 0),
    coalesce(sum((c->>'classes_attended')::int), 0)
  into total_held, total_attended
  from jsonb_array_elements(courses_list) c;

  return jsonb_build_object(
    'overall_percentage', case when total_held > 0 then round((total_attended::numeric / total_held::numeric) * 100, 1) else 0 end,
    'total_classes_held', total_held,
    'total_classes_attended', total_attended,
    'courses', courses_list
  );
end $$;

-- Revoke & Grant function permissions
revoke all on function classmate.teacher_course_attendance_overview(uuid, uuid) from public, anon;
grant execute on function classmate.teacher_course_attendance_overview(uuid, uuid) to authenticated;

revoke all on function classmate.course_attendance_matrix(uuid) from public, anon;
grant execute on function classmate.course_attendance_matrix(uuid) to authenticated;

revoke all on function classmate.student_my_attendance(uuid) from public, anon;
grant execute on function classmate.student_my_attendance(uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
