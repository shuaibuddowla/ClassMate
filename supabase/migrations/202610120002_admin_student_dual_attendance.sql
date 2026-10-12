begin;

-- ============================================================================
-- 1. Allow Admins to belong to an Academic Batch & have Student IDs
-- ============================================================================
alter table classmate.profiles drop constraint if exists profiles_check;

alter table classmate.profiles add constraint profiles_check
  check (
    role in ('student', 'admin')
    or (batch_id is null and student_id is null and not is_cr and cr_batch_id is null)
  );

-- ============================================================================
-- 2. Update Profile Initialization RPC to Preserve Admin Batch & Student Attributes
-- ============================================================================
create or replace function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare 
  u auth.users%rowtype; 
  result classmate.profiles%rowtype; 
  dept uuid;
  v_batch uuid;
  matched text[];
  parsed_student_id text;
  parsed_session smallint;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;
  if lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$' and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your university account or an admin-approved teacher Gmail' using errcode='28000';
  end if;

  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    matched := regexp_match(lower(u.email), '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if matched is not null then
      select d.id into dept from classmate.departments d where d.email_prefix = matched[1] and d.is_active;
      if dept is not null then
        select b.id into v_batch from classmate.batches b 
        where b.department_id = dept and b.academic_session = matched[2]::smallint and b.is_active;
      end if;
      parsed_student_id := upper(matched[1] || matched[2] || matched[3]);
      parsed_session := matched[2]::smallint;
    end if;

    insert into classmate.profiles(id, email, full_name, role, department_id, batch_id, student_id, academic_session, verification_status)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', dept, v_batch, parsed_student_id, parsed_session, 'active')
    on conflict (id) do update set 
      role = 'admin', 
      verification_status = 'active',
      department_id = coalesce(classmate.profiles.department_id, excluded.department_id),
      batch_id = coalesce(classmate.profiles.batch_id, excluded.batch_id),
      student_id = coalesce(classmate.profiles.student_id, excluded.student_id),
      academic_session = coalesce(classmate.profiles.academic_session, excluded.academic_session),
      updated_at = now();
  else
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active')
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', updated_at = now();
    else
      insert into classmate.profiles(id, email, full_name, role, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'student', 'pending')
      on conflict (id) do update set role = 'student',
        verification_status = case when classmate.profiles.role <> 'student'
          then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
        department_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.department_id end,
        updated_at = now();
    end if;
  end if;
  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

-- ============================================================================
-- 3. Include Admin Students in Attendance Matrix & Overview Roster
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
  if not is_admin and not exists (
    select 1 from classmate.teacher_course_assignments tca
    join classmate.semester_courses sc on sc.id = tca.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    where s.batch_id = target_batch and tca.teacher_id = caller_id and tca.active
  ) then
    raise exception 'Unauthorized to view teacher attendance overview' using errcode='42501';
  end if;

  select jsonb_agg(
    jsonb_build_object(
      'offering_id', course_data.offering_id,
      'course_code', course_data.course_code,
      'course_title', course_data.course_title,
      'course_type', course_data.course_type,
      'attendance_enabled', course_data.attendance_enabled,
      'total_sessions', course_data.total_sessions,
      'avg_percentage', case 
        when course_data.total_sessions > 0 and course_data.total_enrolled > 0
        then round((course_data.total_attendees::numeric / (course_data.total_sessions * course_data.total_enrolled)::numeric) * 100, 1)
        else 0 end,
      'last_session_at', course_data.last_session_at,
      'last_session_present', course_data.last_session_present,
      'total_students', course_data.total_enrolled
    ) order by course_data.course_code asc
  ) into res
  from (
    select
      sc.id as offering_id,
      coalesce(sc.code_override, c.course_code) as course_code,
      coalesce(sc.title_override, c.course_title) as course_title,
      coalesce(sc.type_override, c.course_type) as course_type,
      coalesce(sc.attendance_enabled, false) as attendance_enabled,
      count(distinct s.id) as total_sessions,
      coalesce(sum(sess_stat.pres), 0) as total_attendees,
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
      coalesce((
        select count(*)
        from classmate.profiles p
        where p.batch_id = sem.batch_id and p.role in ('student', 'admin') and p.verification_status = 'active'
      ), 0) as total_enrolled
    from classmate.semester_courses sc
    join classmate.semesters sem on sem.id = sc.semester_id
    join classmate.courses c on c.id = sc.course_id
    left join classmate.routine_slots rs on rs.semester_course_id = sc.id
    left join classmate.attendance_sessions s on s.routine_slot_id = rs.id
    left join lateral (
      select count(r.id) as pres
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
-- 4. Set ce25045's Batch to CSE 22 & Student ID to CE25045
-- ============================================================================
do $$
declare
  v_cse_dept uuid;
  v_cse22_batch uuid;
begin
  select id into v_cse_dept from classmate.departments where code = 'cse' limit 1;
  select id into v_cse22_batch from classmate.batches where department_id = v_cse_dept and batch_number = 22 limit 1;

  update classmate.profiles
  set
    department_id = v_cse_dept,
    batch_id = v_cse22_batch,
    student_id = 'CE25045',
    academic_session = 25,
    verification_status = 'active',
    updated_at = now()
  where email = 'ce25045@mbstu.ac.bd';
end $$;

commit;
notify pgrst, 'reload schema';
