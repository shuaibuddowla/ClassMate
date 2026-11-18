-- ============================================================================
-- Migration: 202611160002_fix_attendance_sessions_room_link.sql
-- Description: Fix attendance sessions room matching with official routine timetable:
--              1. Backfill routine_slot_id for past attendance sessions that were null.
--              2. Eliminate hardcoded 'Room 304' fallback in student_my_attendance RPC.
--              3. Dynamic room resolution across course_attendance_matrix and teacher_course_attendance_overview.
--              4. Auto-link routine_slot_id in start_adhoc_attendance_session on today's day of week.
--              5. Include room in view classmate.v_course_attendance_sessions.
-- ============================================================================

begin;

-- 1. Backfill routine_slot_id for all sessions where it is NULL based on course and day of week
update classmate.attendance_sessions s
set routine_slot_id = sub.matched_slot_id
from (
  select s2.id as session_id,
         (
           select rs.id
           from classmate.routine_slots rs
           where rs.semester_course_id = s2.semester_course_id
             and rs.day_of_week = extract(dow from (s2.started_at at time zone 'Asia/Dhaka'))::int
           order by abs(extract(epoch from (rs.start_time - (s2.started_at at time zone 'Asia/Dhaka')::time))) asc
           limit 1
         ) as matched_slot_id
  from classmate.attendance_sessions s2
  where s2.routine_slot_id is null and s2.semester_course_id is not null
) sub
where s.id = sub.session_id and sub.matched_slot_id is not null;

-- 2. Update student_my_attendance RPC to eliminate hardcoded 'Room 304'
create or replace function classmate.student_my_attendance(target_batch uuid default null::uuid)
 returns jsonb
 language plpgsql
 stable security definer
 set search_path to ''
as $function$
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
            'room', coalesce(
              rs.room,
              (
                select rs2.room from classmate.routine_slots rs2
                where rs2.semester_course_id = sc.id
                  and rs2.day_of_week = extract(dow from (sess.started_at at time zone 'Asia/Dhaka'))::int
                limit 1
              ),
              (
                select rs3.room from classmate.routine_slots rs3
                where rs3.semester_course_id = sc.id
                limit 1
              ),
              'Classroom'
            ),
            'status', case when r.id is not null then 'present' else 'absent' end,
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
end $function$;

-- 3. Update course_attendance_matrix RPC to resolve dynamic room if routine_slot_id is null
create or replace function classmate.course_attendance_matrix(target_semester_course uuid)
 returns jsonb
 language plpgsql
 stable security definer
 set search_path to ''
as $function$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  sessions_list jsonb;
  students_list jsonb;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
      is_admin := true;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select s.batch_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch_id, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  if not is_admin and not exists (
    select 1 from classmate.profiles where id = caller_id and role = 'admin'
  ) and not exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
  ) and not exists (
    select 1 from classmate.profiles p
    where p.id = caller_id and p.is_cr = true and p.batch_id = v_batch_id
  ) then
    raise exception 'Unauthorized to view attendance matrix for this course' using errcode='42501';
  end if;

  -- Sessions for this course (both scheduled and ad-hoc)
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'session_id', s.id,
        'started_at', s.started_at,
        'date', (s.started_at at time zone 'Asia/Dhaka')::date,
        'time', to_char((s.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
        'status', s.status,
        'room', coalesce(
          rs.room,
          (
            select rs2.room from classmate.routine_slots rs2
            where rs2.semester_course_id = target_semester_course
              and rs2.day_of_week = extract(dow from (s.started_at at time zone 'Asia/Dhaka'))::int
            limit 1
          ),
          (
            select rs3.room from classmate.routine_slots rs3
            where rs3.semester_course_id = target_semester_course
            limit 1
          ),
          'Classroom'
        ),
        'total_present', coalesce(sess_present.pres_cnt, 0)
      ) order by s.started_at asc
    ),
    '[]'::jsonb
  )
  into sessions_list
  from classmate.attendance_sessions s
  left join classmate.routine_slots rs on rs.id = s.routine_slot_id
  left join lateral (
    select count(distinct r.student_id) as pres_cnt
    from classmate.attendance_records r
    where r.session_id = s.id
  ) sess_present on true
  where (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course);

  -- Enrolled students in batch: Regular students first, Re-add students LAST
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'rfid_card_uid', p.rfid_card_uid,
        'is_readd', classmate.is_readd_student(p.student_id, v_batch_id),
        'records', coalesce(stu_records.rec_map, '{}'::jsonb),
        'present_count', coalesce(stu_records.pres_cnt, 0),
        'total_sessions', jsonb_array_length(sessions_list),
        'percentage', case 
          when jsonb_array_length(sessions_list) > 0 
          then round((coalesce(stu_records.pres_cnt, 0)::numeric / jsonb_array_length(sessions_list)::numeric) * 100, 1)
          else 0 end
      ) order by
          case when classmate.is_readd_student(p.student_id, v_batch_id) then 1 else 0 end asc,
          coalesce(p.student_id, p.full_name) asc
    ),
    '[]'::jsonb
  )
  into students_list
  from classmate.profiles p
  left join lateral (
    select
      jsonb_object_agg(
        r.session_id::text,
        jsonb_build_object(
          'present', true,
          'status', 'P',
          'method', r.method,
          'marked_at', to_char((r.marked_at at time zone 'Asia/Dhaka'), 'HH12:MI AM')
        )
      ) as rec_map,
      count(distinct r.session_id) as pres_cnt
    from classmate.attendance_records r
    join classmate.attendance_sessions s on s.id = r.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    where r.student_id = p.id
      and (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course)
  ) stu_records on true
  where p.batch_id = v_batch_id and p.role in ('student', 'admin') and coalesce(p.verification_status, 'active') = 'active';

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'sessions', sessions_list,
    'students', students_list
  );
end $function$;

-- 4. Update teacher_course_attendance_overview RPC to resolve dynamic room if routine_slot_id is null
create or replace function classmate.teacher_course_attendance_overview(target_batch uuid, target_course uuid default null::uuid)
 returns jsonb
 language plpgsql
 stable security definer
 set search_path to ''
as $function$
declare
  caller_id uuid := auth.uid();
  is_admin boolean := classmate.is_owner();
  caller_role text;
  caller_is_cr boolean := false;
  res jsonb;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
      is_admin := true;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select p.role, coalesce(p.is_cr, false)
  into caller_role, caller_is_cr
  from classmate.profiles p
  where p.id = caller_id;

  if not is_admin and coalesce(caller_role, '') not in ('teacher', 'admin') and not caller_is_cr then
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
          left join classmate.routine_slots rs2 on rs2.id = s2.routine_slot_id
          where s2.semester_course_id = sc.id or rs2.semester_course_id = sc.id
          order by s2.started_at desc limit 1
        )
      ), 0) as last_session_present,
      coalesce(
        (
          select jsonb_agg(
            jsonb_build_object(
              'session_id', s_inner.id,
              'routine_slot_id', s_inner.routine_slot_id,
              'started_at', s_inner.started_at,
              'closed_at', s_inner.closed_at,
              'status', s_inner.status,
              'room', coalesce(
                rs_inner.room,
                (
                  select rs2.room from classmate.routine_slots rs2
                  where rs2.semester_course_id = sc.id
                    and rs2.day_of_week = extract(dow from (s_inner.started_at at time zone 'Asia/Dhaka'))::int
                  limit 1
                ),
                (
                  select rs3.room from classmate.routine_slots rs3
                  where rs3.semester_course_id = sc.id
                  limit 1
                ),
                'Classroom'
              ),
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
          from classmate.attendance_sessions s_inner
          left join classmate.routine_slots rs_inner on rs_inner.id = s_inner.routine_slot_id
          left join lateral (
            select count(r_in.id) as pres_cnt
            from classmate.attendance_records r_in
            where r_in.session_id = s_inner.id
          ) rec_stat on true
          where s_inner.semester_course_id = sc.id or rs_inner.semester_course_id = sc.id
        ),
        '[]'::jsonb
      ) as sessions
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    left join classmate.teacher_directory td on td.id = sc.teacher_record_id
    left join classmate.routine_slots rs on rs.semester_course_id = sc.id
    left join classmate.attendance_sessions s on (s.routine_slot_id = rs.id or s.semester_course_id = sc.id)
    left join lateral (
      select count(r.id) as pres
      from classmate.attendance_records r
      where r.session_id = s.id
    ) sess_stat on true
    where sem.batch_id = target_batch
      and (target_course is null or sc.id = target_course)
      and (is_admin or caller_is_cr or coalesce(sc.attendance_enabled, false) = true)
      and (
        is_admin
        or caller_is_cr
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
  ) course_data;

  return res;
end $function$;

-- 5. Update start_adhoc_attendance_session to auto-link routine_slot_id from timetable
create or replace function classmate.start_adhoc_attendance_session(target_semester_course uuid, target_device text default 'ESP32-READER-01'::text)
 returns jsonb
 language plpgsql
 security definer
 set search_path to ''
as $function$
declare
  caller_id uuid := auth.uid();
  v_batch uuid;
  v_dept uuid;
  v_course_code text;
  v_course_title text;
  v_slot_id uuid;
  sess_id uuid;
  sec text;
begin
  if caller_id is null then
    if current_user in ('postgres', 'supabase_admin') then
      select id into caller_id from classmate.profiles where role = 'admin' limit 1;
    else
      raise exception 'Authentication required' using errcode='42501';
    end if;
  end if;

  select sem.batch_id, b.department_id,
         coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_batch, v_dept, v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.semesters sem on sem.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  join classmate.batches b on b.id = sem.batch_id
  where sc.id = target_semester_course;

  if not found then
    raise exception 'Course offering not found' using errcode='42704';
  end if;

  -- Check permissions (Admin, Owner, assigned teacher, or Batch CR)
  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and role = 'admin')
    or exists (
      select 1 from classmate.teacher_course_assignments tca
      where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
    )
    or exists (
      select 1 from classmate.profiles p
      where p.id = caller_id and p.is_cr = true and p.batch_id = v_batch
    )
  ) then
    raise exception 'Unauthorized to start attendance session for this course' using errcode='42501';
  end if;

  -- Close any existing open session for this course to avoid stale duplicates
  update classmate.attendance_sessions
  set status = 'closed', closed_at = now()
  where semester_course_id = target_semester_course and status = 'open';

  -- Auto-match routine_slot_id from timetable on today's day of week
  select id into v_slot_id
  from classmate.routine_slots
  where semester_course_id = target_semester_course
    and day_of_week = extract(dow from (now() at time zone 'Asia/Dhaka'))::int
  order by abs(extract(epoch from (start_time - (now() at time zone 'Asia/Dhaka')::time))) asc
  limit 1;

  sec := encode(extensions.gen_random_bytes(16), 'hex');

  insert into classmate.attendance_sessions (
    routine_slot_id, semester_course_id, batch_id, started_by, session_secret, status
  ) values (
    v_slot_id, target_semester_course, v_batch, caller_id, sec, 'open'
  )
  returning id into sess_id;

  -- Automatically bind hardware device if specified
  if target_device is not null then
    update classmate.attendance_devices
    set active_session_id = sess_id, last_heartbeat = now()
    where id = target_device;
  end if;

  -- Queue attendance open notification for batch
  insert into classmate.notification_outbox(
    kind, record_id, batch_id, department_id, routing_label
  ) values (
    'attendance_open', sess_id, v_batch, v_dept, classmate.routing_label(v_batch)
  ) on conflict (kind, record_id) do update set
    delivered_at = null,
    jobs_prepared = false,
    attempts = 0,
    created_at = now();

  perform classmate.request_notification_dispatch();

  return jsonb_build_object(
    'session_id', sess_id,
    'session_secret', sec,
    'status', 'open',
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch,
    'routine_slot_id', v_slot_id
  );
end $function$;

-- 6. Update view v_course_attendance_sessions with room at the end
create or replace view classmate.v_course_attendance_sessions as
 select s.id as session_id,
    s.semester_course_id,
    coalesce(sc.code_override, c.course_code) as course_code,
    coalesce(sc.title_override, c.course_title) as course_title,
    s.batch_id,
        case
            when (b.batch_number is not null) then ('Batch '::text || (b.batch_number)::text)
            else 'Batch'::text
        end as batch_name,
    s.started_by,
    p.full_name as started_by_name,
    s.started_at,
    s.closed_at,
    s.status,
    ((s.started_at at time zone 'Asia/Dhaka'::text))::date as session_date,
    to_char((s.started_at at time zone 'Asia/Dhaka'::text), 'HH12:MI AM'::text) as session_time,
    count(ar.id) filter (where (ar.id is not null)) as total_present,
    coalesce(
      rs.room,
      (
        select rs2.room from classmate.routine_slots rs2
        where rs2.semester_course_id = sc.id
          and rs2.day_of_week = extract(dow from (s.started_at at time zone 'Asia/Dhaka'))::int
        limit 1
      ),
      (
        select rs3.room from classmate.routine_slots rs3
        where rs3.semester_course_id = sc.id
        limit 1
      ),
      'Classroom'
    ) as room
   from classmate.attendance_sessions s
     join classmate.semester_courses sc on sc.id = s.semester_course_id
     join classmate.courses c on c.id = sc.course_id
     left join classmate.routine_slots rs on rs.id = s.routine_slot_id
     left join classmate.batches b on b.id = s.batch_id
     left join classmate.profiles p on p.id = s.started_by
     left join classmate.attendance_records ar on ar.session_id = s.id
  group by s.id, sc.id, sc.code_override, c.course_code, sc.title_override, c.course_title, s.batch_id, b.batch_number, s.started_by, p.full_name, s.started_at, s.closed_at, s.status, rs.room;

commit;
