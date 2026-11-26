-- Fix teacher_course_attendance_overview average_percentage Cartesian multiplication
-- caused by joining routine_slots in the outer query before summing session present counts.

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
  v_enrolled integer := 0;
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

  select count(*)
  into v_enrolled
  from classmate.profiles p
  where p.batch_id = target_batch
    and p.role in ('student', 'admin')
    and p.verification_status = 'active';

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
      'enrolled_students', v_enrolled,
      'average_percentage', course_data.avg_percentage,
      'last_session_at', course_data.last_session_at,
      'last_session_present', course_data.last_session_present,
      'sessions', course_data.sessions
    ) order by (course_data.total_sessions > 0) desc, course_data.course_code asc
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
      coalesce(agg.total_sessions, 0) as total_sessions,
      case
        when coalesce(agg.total_sessions, 0) > 0 and v_enrolled > 0
        then least(
          100.0,
          round((coalesce(agg.total_present, 0)::numeric / (agg.total_sessions * v_enrolled)::numeric) * 100, 1)
        )
        else 0
      end as avg_percentage,
      agg.last_session_at as last_session_at,
      coalesce((
        select count(distinct r.student_id)
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
              'total_count', v_enrolled,
              'percentage', case
                when v_enrolled > 0
                then least(100.0, round((coalesce(rec_stat.pres_cnt, 0)::numeric / v_enrolled::numeric) * 100, 1))
                else 0 end
            ) order by s_inner.started_at desc
          )
          from classmate.attendance_sessions s_inner
          left join classmate.routine_slots rs_inner on rs_inner.id = s_inner.routine_slot_id
          left join lateral (
            select count(distinct r_in.student_id) as pres_cnt
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
    left join lateral (
      select
        count(distinct s_uniq.id) as total_sessions,
        max(s_uniq.started_at) as last_session_at,
        coalesce(sum(rec_stat.pres), 0) as total_present
      from (
        select distinct s_in.id, s_in.started_at
        from classmate.attendance_sessions s_in
        left join classmate.routine_slots rs_in on rs_in.id = s_in.routine_slot_id
        where s_in.semester_course_id = sc.id or rs_in.semester_course_id = sc.id
      ) s_uniq
      left join lateral (
        select count(distinct r.student_id) as pres
        from classmate.attendance_records r
        where r.session_id = s_uniq.id
      ) rec_stat on true
    ) agg on true
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
  ) course_data;

  return res;
end $function$;

revoke all on function classmate.teacher_course_attendance_overview(uuid, uuid) from public, anon;
grant execute on function classmate.teacher_course_attendance_overview(uuid, uuid) to authenticated;
