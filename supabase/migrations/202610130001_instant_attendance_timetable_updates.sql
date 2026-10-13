-- 202610130001_instant_attendance_timetable_updates.sql
-- 1. Update timetable_details to return attendance_enabled
-- 2. Add lightweight, instant active_attendance_sessions RPC

begin;

drop function if exists classmate.timetable_details(uuid, date, uuid[]);

create function classmate.timetable_details(target_batch uuid, target_date date, target_course_ids uuid[])
returns table(
  semester_course_id uuid,
  teacher_name text,
  cancelled boolean,
  attendance_enabled boolean
)
language sql stable security definer set search_path='' as $$
  select
    sc.id,
    coalesce(nullif(trim(t.full_name),''),
      (select nullif(trim(p.full_name),'') from classmate.teacher_course_assignments a
        join classmate.profiles p on p.id=a.teacher_id
        join classmate.teacher_allowlist al on al.email=p.email and al.department_id=p.department_id and al.active
        where a.semester_course_id=sc.id and a.active and p.role='teacher' and p.verification_status='active'
        order by a.created_at limit 1)),
    exists(select 1 from classmate.class_changes cc where cc.batch_id=target_batch
      and cc.semester_course_id=sc.id and cc.effective_date=target_date and cc.kind='cancelled'),
    coalesce(sc.attendance_enabled, false)
  from classmate.semester_courses sc
  join classmate.semesters s on s.id=sc.semester_id
  left join classmate.teacher_directory t on t.id=sc.teacher_record_id
  where sc.id=any(target_course_ids) and s.batch_id=target_batch and s.status='active'
    and classmate.can_read_batch(target_batch);
$$;

revoke all on function classmate.timetable_details(uuid, date, uuid[]) from public, anon;
grant execute on function classmate.timetable_details(uuid, date, uuid[]) to authenticated;

-- Lightweight batch-aware active attendance session locator
drop function if exists classmate.active_attendance_sessions(uuid);

create function classmate.active_attendance_sessions(target_batch uuid)
returns table(
  session_id uuid,
  routine_slot_id uuid,
  semester_course_id uuid,
  started_at timestamptz,
  status text,
  present_count bigint
)
language sql stable security definer set search_path='' as $$
  select
    s.id as session_id,
    s.routine_slot_id,
    rs.semester_course_id,
    s.started_at,
    s.status,
    count(r.id) as present_count
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.semesters sem on sem.id = sc.semester_id
  left join classmate.attendance_records r on r.session_id = s.id
  where sem.batch_id = target_batch
    and s.status = 'open'
    and classmate.can_read_batch(target_batch)
  group by s.id, s.routine_slot_id, rs.semester_course_id, s.started_at, s.status;
$$;

revoke all on function classmate.active_attendance_sessions(uuid) from public, anon;
grant execute on function classmate.active_attendance_sessions(uuid) to authenticated;

commit;
