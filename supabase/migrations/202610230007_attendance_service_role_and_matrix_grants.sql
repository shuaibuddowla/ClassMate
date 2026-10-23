-- Migration: Ensure full service_role and authenticated permissions for attendance tables and RPCs
-- Date: 2026-10-23
-- Fixes: 42501 permission denied for table attendance_sessions and function course_attendance_matrix when accessed by serverless endpoints
-- Fixes: 42803 aggregate function calls cannot be nested in course_attendance_matrix

grant usage on schema classmate to service_role, authenticated;

-- Grant all permissions on all tables and sequences in classmate schema to service_role
grant all on all tables in schema classmate to service_role;
grant all on all sequences in schema classmate to service_role;
grant execute on all functions in schema classmate to service_role;

-- Grant execute on all functions to authenticated
grant execute on all functions in schema classmate to authenticated;

-- Grant table privileges to authenticated for web client operations
grant select, insert, update on classmate.attendance_sessions to authenticated;
grant select, insert, update on classmate.attendance_records to authenticated;
grant select, insert, update on classmate.attendance_devices to authenticated;
grant select, insert, update on classmate.unregistered_rfid_scans to authenticated;

do $$
begin
  if exists (select from information_schema.tables where table_schema = 'classmate' and table_name = 'feature_flags') then
    grant select, insert, update on classmate.feature_flags to authenticated;
  end if;
end $$;

-- Update course_attendance_matrix with CTE to fix nested aggregate bug and allow service_role access
create or replace function classmate.course_attendance_matrix(
  target_semester_course uuid
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
  is_service_role boolean := ((select auth.role()) = 'service_role');
  is_admin boolean := is_service_role or classmate.is_owner();
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  sessions_list jsonb;
  students_list jsonb;
begin
  if not is_admin and not exists (
    select 1 from classmate.profiles where id = caller_id and role = 'admin'
  ) and not exists (
    select 1 from classmate.teacher_course_assignments tca
    where tca.semester_course_id = target_semester_course and tca.teacher_id = caller_id and tca.active
  ) then
    raise exception 'Unauthorized to view attendance matrix for this course' using errcode='42501';
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

  -- Sessions for this course (both scheduled and ad-hoc)
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'session_id', s.id,
        'started_at', s.started_at,
        'date', (s.started_at at time zone 'Asia/Dhaka')::date,
        'time', to_char((s.started_at at time zone 'Asia/Dhaka'), 'HH12:MI AM'),
        'total_present', (select count(*) from classmate.attendance_records r where r.session_id = s.id)
      ) order by s.started_at asc
    ),
    '[]'::jsonb
  ) into sessions_list
  from classmate.attendance_sessions s
  left join classmate.routine_slots rs on rs.id = s.routine_slot_id
  where (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course);

  -- Student Attendance Matrix for the batch (using CTE to prevent 42803 nested aggregation)
  with student_stats as (
    select 
      p.id as student_id,
      p.full_name,
      p.student_id as roll,
      p.rfid_card_uid,
      count(r.id) as present_count,
      coalesce(
        (
          select jsonb_object_agg(
            ar.session_id::text,
            jsonb_build_object(
              'present', true,
              'method', ar.method,
              'marked_at', to_char((ar.marked_at at time zone 'Asia/Dhaka'), 'HH12:MI AM')
            )
          )
          from classmate.attendance_records ar
          where ar.student_id = p.id
            and ar.session_id in (
              select s.id from classmate.attendance_sessions s
              left join classmate.routine_slots rs on rs.id = s.routine_slot_id
              where (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course)
            )
        ),
        '{}'::jsonb
      ) as records
    from classmate.profiles p
    left join classmate.attendance_records r on r.student_id = p.id and r.session_id in (
      select s.id from classmate.attendance_sessions s
      left join classmate.routine_slots rs on rs.id = s.routine_slot_id
      where (s.semester_course_id = target_semester_course or rs.semester_course_id = target_semester_course)
    )
    where p.batch_id = v_batch_id and p.role = 'student' and p.verification_status = 'active'
    group by p.id, p.full_name, p.student_id, p.rfid_card_uid
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', ss.student_id,
        'full_name', ss.full_name,
        'roll', ss.roll,
        'rfid_card_uid', ss.rfid_card_uid,
        'present_count', ss.present_count,
        'records', ss.records
      ) order by ss.roll asc, ss.full_name asc
    ),
    '[]'::jsonb
  ) into students_list
  from student_stats ss;

  return jsonb_build_object(
    'semester_course_id', target_semester_course,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch_id,
    'sessions', sessions_list,
    'students', students_list
  );
end $$;

grant execute on function classmate.course_attendance_matrix(uuid) to authenticated, service_role;

-- Default privileges for any future tables/sequences/functions in schema classmate
alter default privileges in schema classmate grant all on tables to service_role;
alter default privileges in schema classmate grant all on sequences to service_role;
alter default privileges in schema classmate grant execute on functions to service_role;
