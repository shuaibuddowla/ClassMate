-- ============================================================================
-- Migration: 202610270003_course_matrix_rec_map_status.sql
-- Description:
-- Explicitly add 'status': 'P' in course_attendance_matrix rec_map jsonb
-- ============================================================================

begin;

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
        'room', coalesce(rs.room, 'Station'),
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

  -- Enrolled students in batch
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'rfid_card_uid', p.rfid_card_uid,
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
end $$;

grant execute on function classmate.course_attendance_matrix(uuid) to authenticated;

commit;
notify pgrst, 'reload schema';
