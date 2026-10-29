-- ============================================================================
-- Migration: 202610270008_sort_readd_students_last.sql
-- Description:
-- Ensure re-admitted (re-add / batch override / older session cohort) students
-- appear at the very LAST of every student list across the application.
-- ============================================================================

begin;

-- 1. Helper function to determine if a student is a re-admitted (re-add) student
create or replace function classmate.is_readd_student(p_student_id text, p_batch_id uuid default null)
returns boolean
language plpgsql stable security definer set search_path='' as $$
declare
  v_is_override boolean;
  v_batch_session integer;
  v_stu_session integer;
begin
  if p_student_id is null or trim(p_student_id) = '' or p_student_id = 'null' then
    return false;
  end if;

  -- 1. Check explicit batch_overrides table
  select exists(
    select 1 from classmate.batch_overrides bo
    where upper(trim(bo.student_id)) = upper(trim(p_student_id))
  ) into v_is_override;

  if v_is_override then
    return true;
  end if;

  -- 2. Check student ID prefix session vs batch session
  if p_batch_id is not null then
    select b.academic_session into v_batch_session
    from classmate.batches b where b.id = p_batch_id;

    -- Extract 2-digit session number from student_id, e.g. CE24014 -> 24
    if p_student_id ~ '^[A-Za-z]+([0-9]{2})[0-9]+' then
      v_stu_session := substring(p_student_id from '^[A-Za-z]+([0-9]{2})')::integer;
      if v_batch_session is not null and v_stu_session < v_batch_session then
        return true;
      end if;
    end if;
  end if;

  return false;
end;
$$;

grant execute on function classmate.is_readd_student(text, uuid) to authenticated, anon, service_role;

-- 2. Update classmate.batch_friends: Re-add students sort to the end of the student list
drop function if exists classmate.batch_friends(uuid,text,integer);

create or replace function classmate.batch_friends(
  target_batch uuid,
  query_text text default '',
  result_offset integer default 0
)
returns table(
  profile_id uuid,
  full_name text,
  student_id text,
  role text,
  is_cr boolean,
  avatar_url text,
  is_online boolean,
  blood_group text,
  is_readd boolean
)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'This batch directory is unavailable' using errcode='42501';
  end if;
  if result_offset < 0 or result_offset is null or length(query_text) > 100 then
    raise exception 'Invalid directory query' using errcode='22023';
  end if;

  return query select
    p.id,
    p.full_name,
    p.student_id,
    p.role::text,
    p.is_cr,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,
    exists(select 1 from classmate.active_presence ap where ap.profile_id=p.id and ap.last_seen_at > now() - interval '60 seconds'),
    p.blood_group,
    classmate.is_readd_student(p.student_id, target_batch) as is_readd
  from classmate.profiles p join auth.users u on u.id=p.id
  where p.verification_status='active' and (
    (p.role='student' and p.batch_id=target_batch and p.department_id=(select department_id from classmate.batches where id=target_batch))
    or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.active and o.email=p.email)
      and exists(select 1 from classmate.batches b join classmate.departments d on d.id=b.department_id
        where b.id=target_batch and (
          p.batch_id=b.id or (p.batch_id is null
            and p.email ~ '^[a-z]+[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$'
            and d.email_prefix=substring(p.email from '^([a-z]+)[0-9]')
            and b.academic_session=substring(p.email from '^[a-z]+([0-9]{2})')::integer
            and b.batch_number=b.academic_session-d.session_offset)))))
    and position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')||' '||coalesce(p.blood_group,'')||' '||replace(coalesce(p.blood_group,''),'+',' positive')||' '||replace(coalesce(p.blood_group,''),'-',' negative')))>0
  order by
    (p.role='admin') desc,
    -- Regular students first (0), re-admitted students last (1)
    case when classmate.is_readd_student(p.student_id, target_batch) then 1 else 0 end asc,
    case when p.student_id is not null and trim(p.student_id) <> '' and p.student_id <> 'null' then 0 else 1 end,
    p.student_id asc,
    p.full_name asc,
    p.id asc
  limit 100 offset result_offset;
end $$;

revoke all on function classmate.batch_friends(uuid,text,integer) from public,anon;
grant execute on function classmate.batch_friends(uuid,text,integer) to authenticated;

-- 3. Update classmate.admin_batch_students: Re-add students sort last and include is_readd
create or replace function classmate.admin_batch_students(target_batch uuid)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  caller_id uuid := auth.uid();
begin
  if not (
    current_user in ('postgres', 'supabase_admin')
    or classmate.is_owner()
    or exists (select 1 from classmate.profiles where id = caller_id and (role = 'admin' or is_cr))
  ) then
    raise exception 'Unauthorized' using errcode='42501';
  end if;

  return coalesce((
    select jsonb_agg(
      jsonb_build_object(
        'student_id', p.id,
        'roll', coalesce(p.student_id, ''),
        'full_name', p.full_name,
        'rfid_card_uid', p.rfid_card_uid,
        'is_readd', classmate.is_readd_student(p.student_id, target_batch)
      ) order by
          case when classmate.is_readd_student(p.student_id, target_batch) then 1 else 0 end asc,
          coalesce(p.student_id, p.full_name) asc
    )
    from classmate.profiles p
    where p.batch_id = target_batch and p.role in ('student', 'admin') and p.verification_status = 'active'
  ), '[]'::jsonb);
end $$;

revoke all on function classmate.admin_batch_students(uuid) from public, anon;
grant execute on function classmate.admin_batch_students(uuid) to authenticated;

-- 4. Update classmate.course_attendance_matrix: Re-add students sort last and include is_readd
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
end $$;

grant execute on function classmate.course_attendance_matrix(uuid) to authenticated;

-- 5. Update classmate.get_session_summary: Re-add students sort last and include is_readd
create or replace function classmate.get_session_summary(target_session uuid)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  sess classmate.attendance_sessions%rowtype;
  roster jsonb;
  v_course_code text;
  v_course_title text;
  v_batch_id uuid;
begin
  select * into sess from classmate.attendance_sessions where id = target_session;
  if not found then raise exception 'Session not found' using errcode='P0002'; end if;

  -- Resolve batch_id and course details
  v_batch_id := sess.batch_id;
  if sess.semester_course_id is not null then
    select coalesce(sc.code_override, c.course_code), coalesce(sc.title_override, c.course_title), coalesce(v_batch_id, sem.batch_id)
    into v_course_code, v_course_title, v_batch_id
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    where sc.id = sess.semester_course_id;
  elsif sess.routine_slot_id is not null then
    select coalesce(sc.code_override, c.course_code), coalesce(sc.title_override, c.course_title), coalesce(v_batch_id, sem.batch_id)
    into v_course_code, v_course_title, v_batch_id
    from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    where rs.id = sess.routine_slot_id;
  end if;

  -- Build complete roster: Regular students first, Re-add students LAST
  select jsonb_agg(jsonb_build_object(
    'student_id', p.id,
    'full_name', p.full_name,
    'roll_number', coalesce(p.student_id, ''),
    'status', case when r.id is not null then 'present' else 'absent' end,
    'marked_at', r.marked_at,
    'method', coalesce(r.method, 'none'),
    'confidence', coalesce(r.confidence, 'none'),
    'rfid_card_uid', p.rfid_card_uid,
    'is_readd', classmate.is_readd_student(p.student_id, v_batch_id)
  ) order by
      case when classmate.is_readd_student(p.student_id, v_batch_id) then 1 else 0 end asc,
      coalesce(p.student_id, p.full_name) asc)
  into roster
  from classmate.profiles p
  left join classmate.attendance_records r on r.session_id = target_session and r.student_id = p.id
  where (
    (v_batch_id is not null and p.batch_id = v_batch_id)
    or (v_batch_id is null and r.id is not null)
  )
  and p.role in ('student', 'admin')
  and coalesce(p.verification_status, 'active') = 'active';

  return jsonb_build_object(
    'session_id', sess.id,
    'routine_slot_id', sess.routine_slot_id,
    'semester_course_id', sess.semester_course_id,
    'course_code', v_course_code,
    'course_title', v_course_title,
    'batch_id', v_batch_id,
    'status', sess.status,
    'started_at', sess.started_at,
    'closed_at', sess.closed_at,
    'roster', coalesce(roster, '[]'::jsonb)
  );
end $$;

grant execute on function classmate.get_session_summary(uuid) to authenticated, anon;

-- 6. Update classmate.get_device_session_roster: Re-add students sort last in device cache
create or replace function classmate.get_device_session_roster(p_device_id text)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  dev record;
  sess record;
  v_batch_id uuid;
  v_course_code text;
  v_course_title text;
  v_students jsonb;
begin
  select * into dev from classmate.attendance_devices where id = p_device_id;
  if not found or dev.active_session_id is null then
    -- Return registered card cache sorted with re-add students last
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'u', upper(trim(p.rfid_card_uid)),
          'i', coalesce(p.student_id, 'STUDENT'),
          'p', false,
          'r', classmate.is_readd_student(p.student_id, p.batch_id)
        ) order by
            case when classmate.is_readd_student(p.student_id, p.batch_id) then 1 else 0 end asc,
            p.student_id asc
      ),
      '[]'::jsonb
    )
    into v_students
    from classmate.profiles p
    where p.rfid_card_uid is not null
      and trim(p.rfid_card_uid) <> '';

    return jsonb_build_object(
      'status', 'SLEEP',
      'students', v_students
    );
  end if;

  select s.id, s.status, s.routine_slot_id, s.semester_course_id, s.batch_id
  into sess
  from classmate.attendance_sessions s
  where s.id = dev.active_session_id;

  if not found or sess.status <> 'open' then
    update classmate.attendance_devices set active_session_id = null where id = p_device_id;
    return jsonb_build_object('status', 'SLEEP');
  end if;

  -- Resolve batch_id
  v_batch_id := sess.batch_id;
  if v_batch_id is null and sess.semester_course_id is not null then
    select sem.batch_id into v_batch_id
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    where sc.id = sess.semester_course_id;
  end if;
  if v_batch_id is null and sess.routine_slot_id is not null then
    select sem.batch_id into v_batch_id
    from classmate.routine_slots rs
    join classmate.semester_courses sc on sc.id = rs.semester_course_id
    join classmate.semesters sem on sem.id = sc.semester_id
    where rs.id = sess.routine_slot_id;
  end if;

  -- Course details
  select coalesce(sc.code_override, c.course_code),
         coalesce(sc.title_override, c.course_title)
  into v_course_code, v_course_title
  from classmate.semester_courses sc
  join classmate.courses c on c.id = sc.course_id
  where sc.id = coalesce(sess.semester_course_id, (select semester_course_id from classmate.routine_slots where id = sess.routine_slot_id));

  -- Collect students in this batch who have registered RFID cards
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'u', upper(trim(p.rfid_card_uid)),
        'i', coalesce(p.student_id, 'STUDENT'),
        'p', exists(select 1 from classmate.attendance_records ar where ar.session_id = sess.id and ar.student_id = p.id),
        'r', classmate.is_readd_student(p.student_id, v_batch_id)
      ) order by
          case when classmate.is_readd_student(p.student_id, v_batch_id) then 1 else 0 end asc,
          p.student_id asc
    ),
    '[]'::jsonb
  )
  into v_students
  from classmate.profiles p
  where (v_batch_id is null or p.batch_id = v_batch_id)
    and p.rfid_card_uid is not null
    and trim(p.rfid_card_uid) <> '';

  return jsonb_build_object(
    'status', 'ACTIVE',
    'session_id', sess.id,
    'course_code', coalesce(v_course_code, 'CLASS'),
    'course_title', coalesce(v_course_title, 'Class Attendance'),
    'students', v_students
  );
end $$;

grant usage on schema classmate to anon, authenticated, service_role;
grant execute on function classmate.get_device_session_roster(text) to anon, authenticated, service_role;

commit;
notify pgrst, 'reload schema';
