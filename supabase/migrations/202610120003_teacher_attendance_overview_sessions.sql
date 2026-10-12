begin;

-- ============================================================================
-- 1. Helper: can_manage_attendance_slot (Supports both TCA and teacher_directory)
-- ============================================================================
create or replace function classmate.can_manage_attendance_slot(target_slot uuid)
returns boolean
language sql stable security definer set search_path='' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    left join classmate.teacher_course_assignments tca on tca.semester_course_id = sc.id and tca.teacher_id = auth.uid() and tca.active
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.profiles p on p.id = auth.uid()
    where rs.id = target_slot
      and (
        tca.id is not null
        or td.profile_id = auth.uid()
        or lower(td.email) = lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      )
  );
$$;

-- ============================================================================
-- 2. teacher_course_attendance_overview (No 42501 on empty courses; returns sessions array)
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
  caller_role text;
  res jsonb;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode='42501';
  end if;

  select p.role into caller_role from classmate.profiles p where p.id = caller_id;

  -- Only teachers, admins, or app owners can query the teacher attendance overview
  if not is_admin and coalesce(caller_role, '') not in ('teacher', 'admin') then
    raise exception 'Unauthorized to view teacher attendance overview' using errcode='42501';
  end if;

  select coalesce(jsonb_agg(
    jsonb_build_object(
      'semester_course_id', course_data.offering_id,
      'offering_id', course_data.offering_id,
      'course_code', course_data.course_code,
      'course_title', course_data.course_title,
      'course_type', course_data.course_type,
      'credit', course_data.credit,
      'teacher_name', course_data.teacher_name,
      'attendance_enabled', course_data.attendance_enabled,
      'total_sessions', course_data.total_sessions,
      'enrolled_students', course_data.total_enrolled,
      'average_percentage', course_data.avg_percentage,
      'last_session_at', course_data.last_session_at,
      'last_session_present', course_data.last_session_present,
      'sessions', course_data.sessions
    ) order by course_data.course_code asc
  ), '[]'::jsonb) into res
  from (
    select
      sc.id as offering_id,
      coalesce(sc.code_override, c.course_code) as course_code,
      coalesce(sc.title_override, c.course_title) as course_title,
      coalesce(sc.type_override, c.course_type) as course_type,
      coalesce(sc.credit_override, c.credit, 3.0) as credit,
      coalesce(td.full_name, 'Instructor') as teacher_name,
      coalesce(sc.attendance_enabled, false) as attendance_enabled,
      count(distinct s.id) as total_sessions,
      coalesce(
        (
          select count(*)
          from classmate.profiles p
          where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
        ), 0
      ) as total_enrolled,
      case 
        when count(distinct s.id) > 0 and (
          select count(*) from classmate.profiles p 
          where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
        ) > 0
        then round((coalesce(sum(sess_stat.pres), 0)::numeric / (
          count(distinct s.id) * (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active')
        )::numeric) * 100, 1)
        else 0 end as avg_percentage,
      max(s.started_at) as last_session_at,
      coalesce((
        select count(r.id)
        from classmate.attendance_records r
        where r.session_id = (
          select s2.id from classmate.attendance_sessions s2
          join classmate.routine_slots rs2 on rs2.id = s2.routine_slot_id
          where rs2.semester_course_id = sc.id
          order by s2.started_at desc limit 1
        )
      ), 0) as last_session_present,
      coalesce(
        (
          select jsonb_agg(
            jsonb_build_object(
              'session_id', s_inner.id,
              'routine_slot_id', rs_inner.id,
              'started_at', s_inner.started_at,
              'closed_at', s_inner.closed_at,
              'status', s_inner.status,
              'room', rs_inner.room,
              'present_count', coalesce(rec_stat.pres_cnt, 0),
              'total_count', (
                select count(*)
                from classmate.profiles p
                where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
              ),
              'percentage', case
                when (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active') > 0
                then round((coalesce(rec_stat.pres_cnt, 0)::numeric / (select count(*) from classmate.profiles p where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active')::numeric) * 100, 1)
                else 0 end
            ) order by s_inner.started_at desc
          )
          from classmate.routine_slots rs_inner
          join classmate.attendance_sessions s_inner on s_inner.routine_slot_id = rs_inner.id
          left join lateral (
            select count(r_in.id) as pres_cnt
            from classmate.attendance_records r_in
            where r_in.session_id = s_inner.id
          ) rec_stat on true
          where rs_inner.semester_course_id = sc.id
        ),
        '[]'::jsonb
      ) as sessions
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.routine_slots rs on rs.semester_course_id = sc.id
    left join classmate.attendance_sessions s on s.routine_slot_id = rs.id
    left join lateral (
      select count(r.id) as pres
      from classmate.attendance_records r
      where r.session_id = s.id
    ) sess_stat on true
    where sem.batch_id = target_batch
      and (target_course is null or sc.id = target_course)
      and (is_admin or coalesce(sc.attendance_enabled, false) = true)
      and (
        is_admin
        or exists (
          select 1 from classmate.teacher_course_assignments tca
          where tca.semester_course_id = sc.id and tca.teacher_id = caller_id and tca.active
        )
        or exists (
          select 1 from classmate.teacher_directory td_match
          join classmate.profiles pr on pr.id = caller_id
          where td_match.id = sc.teacher_record_id
            and (
              td_match.profile_id = caller_id
              or lower(td_match.email) = lower(pr.email)
              or split_part(lower(td_match.email), '@', 1) = split_part(lower(pr.email), '@', 1)
            )
        )
      )
    group by sc.id, c.course_code, c.course_title, c.course_type, c.credit, td.full_name, sem.batch_id
    order by c.course_code asc
  ) course_data;

  return coalesce(res, '[]'::jsonb);
end $$;

-- ============================================================================
-- 3. course_attendance_matrix (Supports TCA, teacher_directory, admin students)
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
  if not is_admin and not exists (
    select 1 from classmate.semester_courses sc
    left join classmate.teacher_course_assignments tca on tca.semester_course_id = sc.id and tca.teacher_id = caller_id and tca.active
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.profiles p on p.id = caller_id
    where sc.id = target_semester_course
      and (
        tca.id is not null
        or td.profile_id = caller_id
        or lower(td.email) = lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      )
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
  where p.batch_id = v_batch_id and p.role in ('student', 'admin') and p.verification_status = 'active';

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'sessions', sessions_list,
    'students', students_list
  );
end $$;

-- ============================================================================
-- 4. get_session_summary (Includes admin students)
-- ============================================================================
create or replace function classmate.get_session_summary(target_session uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
  roster jsonb;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found'; end if;

  select jsonb_agg(jsonb_build_object(
    'student_id', p.id,
    'full_name', p.full_name,
    'roll_number', coalesce(p.student_id, ''),
    'status', case when r.id is not null then 'present' else 'absent' end,
    'marked_at', r.marked_at,
    'method', coalesce(r.method, 'none'),
    'confidence', coalesce(r.confidence, 'none')
  ) order by coalesce(p.student_id, p.full_name) asc)
  into roster
  from classmate.routine_slots rs
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.profiles p on p.batch_id = s.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
  left join classmate.attendance_records r on r.session_id = target_session and r.student_id = p.id
  where rs.id = sess.routine_slot_id;

  return jsonb_build_object(
    'session_id', sess.id,
    'routine_slot_id', sess.routine_slot_id,
    'status', sess.status,
    'started_at', sess.started_at,
    'closed_at', sess.closed_at,
    'roster', coalesce(roster, '[]'::jsonb)
  );
end $$;

-- ============================================================================
-- 5. available_batches (Teacher sees batches assigned via TCA or teacher_directory)
-- ============================================================================
create or replace function classmate.available_batches()
returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  return coalesce((select jsonb_agg(to_jsonb(q)) from(select b.id,b.department_id,b.batch_number,b.academic_session,
    coalesce(b.calendar_in_timetable, true) as calendar_in_timetable,
    jsonb_build_object('code',d.code,'name',d.name) departments
  from classmate.batches b join classmate.departments d on d.id=b.department_id
  where b.is_active and classmate.can_read_batch(b.id) and (
    classmate.is_owner()
    or not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher')
    or exists(
      select 1 from classmate.teacher_course_assignments a
      join classmate.semester_courses sc on sc.id=a.semester_course_id
      join classmate.semesters s on s.id=sc.semester_id
      where a.teacher_id=auth.uid() and a.active and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
    or exists(
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id=sc.semester_id
      join classmate.teacher_directory td on td.id=sc.teacher_record_id
      join classmate.profiles p on p.id=auth.uid()
      where (
        td.profile_id=auth.uid()
        or lower(td.email)=lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      ) and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
  )
  order by d.code,b.batch_number)q),'[]'::jsonb);
end $$;

-- ============================================================================
-- 6. student_my_attendance (Only returns courses with attendance_enabled = true)
-- ============================================================================
create or replace function classmate.student_my_attendance(target_batch uuid default null)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  v_batch uuid := target_batch;
  courses_list jsonb;
  total_held int := 0;
  total_attended int := 0;
begin
  if not classmate.is_active() then
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
        'attendance_enabled', coalesce(sc.attendance_enabled, false),
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
            'room', coalesce(rs.room, 'TBA'),
            'status', case when r.id is not null then 'present' else 'absent' end,
            'marked_at', r.marked_at
          ) order by sess.started_at desc
        ) filter (where sess.id is not null),
        '[]'::jsonb
      ) as history
    from classmate.routine_slots rs
    join classmate.attendance_sessions sess on sess.routine_slot_id = rs.id
    left join classmate.attendance_records r on r.session_id = sess.id and r.student_id = caller_id
    where rs.semester_course_id = sc.id
  ) c_stat on true
  where s.batch_id = v_batch and s.status in ('active', 'not_started')
    and coalesce(sc.attendance_enabled, false) = true;

  -- Calculate overall metrics across enabled courses
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

-- ============================================================================
-- 7. RLS Policies on Attendance Tables
-- ============================================================================
drop policy if exists manage_attendance_sessions on classmate.attendance_sessions;
create policy manage_attendance_sessions on classmate.attendance_sessions
  for all to authenticated
  using (classmate.can_manage_attendance_slot(routine_slot_id));

drop policy if exists view_attendance_records on classmate.attendance_records;
create policy view_attendance_records on classmate.attendance_records
  for select to authenticated
  using (
    classmate.is_owner()
    or student_id = auth.uid()
    or exists (
      select 1 from classmate.attendance_sessions s
      where s.id = attendance_records.session_id
        and (s.started_by = auth.uid() or classmate.can_manage_attendance_slot(s.routine_slot_id))
    )
  );

commit;
notify pgrst, 'reload schema';
