-- ============================================================================
-- Migration: 202610270007_coursewise_attendance_integrity_and_views.sql
-- Description:
-- Strengthens attendance database integrity and prevents scattered or fragmented data:
-- 1. Changes routine_slot_id foreign key from CASCADE to SET NULL so routine/timetable edits never delete past attendance.
-- 2. Changes semester_course_id foreign key to RESTRICT to protect historical course attendance from accidental course deletion.
-- 3. Trigger trg_ensure_session_course_and_batch ensures every attendance session is strictly bound to a course and batch.
-- 4. High-performance indexes for fast course-wise queries and exports.
-- 5. Standardized views: v_course_attendance_sessions & v_course_student_summary for structured course-wise reporting.
-- 6. RPC get_student_course_attendance_overview for student course-wise breakdown.
-- ============================================================================

begin;

-- 1. Ensure foreign key on routine_slot_id is ON DELETE SET NULL
alter table classmate.attendance_sessions
  drop constraint if exists attendance_sessions_routine_slot_id_fkey,
  add constraint attendance_sessions_routine_slot_id_fkey
    foreign key (routine_slot_id) references classmate.routine_slots(id)
    on delete set null;

-- 2. Ensure foreign key on semester_course_id is ON DELETE RESTRICT (Prevents course deletion while attendance exists)
alter table classmate.attendance_sessions
  drop constraint if exists attendance_sessions_semester_course_id_fkey,
  add constraint attendance_sessions_semester_course_id_fkey
    foreign key (semester_course_id) references classmate.semester_courses(id)
    on delete restrict;

-- 3. Trigger to guarantee every attendance session has both semester_course_id and batch_id
create or replace function classmate.trg_enforce_session_course_and_batch()
returns trigger
language plpgsql as $$
begin
  -- Resolve semester_course_id from routine_slot if missing
  if NEW.semester_course_id is null and NEW.routine_slot_id is not null then
    select semester_course_id into NEW.semester_course_id
    from classmate.routine_slots where id = NEW.routine_slot_id;
  end if;

  -- Resolve batch_id from semester_courses -> semesters if missing
  if NEW.batch_id is null and NEW.semester_course_id is not null then
    select s.batch_id into NEW.batch_id
    from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = NEW.semester_course_id;
  end if;

  if NEW.semester_course_id is null then
    raise exception 'Attendance session must be associated with a course (semester_course_id cannot be null)' using errcode='23502';
  end if;

  if NEW.batch_id is null then
    raise exception 'Attendance session must be associated with a batch (batch_id cannot be null)' using errcode='23502';
  end if;

  return NEW;
end;
$$;

drop trigger if exists trg_ensure_session_course_and_batch on classmate.attendance_sessions;
create trigger trg_ensure_session_course_and_batch
  before insert or update on classmate.attendance_sessions
  for each row
  execute function classmate.trg_enforce_session_course_and_batch();

-- 4. High-performance composite indexes for course-wise reporting
create index if not exists attendance_sessions_course_date_idx
  on classmate.attendance_sessions (semester_course_id, started_at desc);

create index if not exists attendance_records_session_marked_idx
  on classmate.attendance_records (session_id, marked_at desc);

-- 5. Unified View: Course-wise Attendance Sessions
create or replace view classmate.v_course_attendance_sessions as
select
  s.id as session_id,
  s.semester_course_id,
  coalesce(sc.code_override, c.course_code) as course_code,
  coalesce(sc.title_override, c.course_title) as course_title,
  s.batch_id,
  case when b.batch_number is not null then 'Batch ' || b.batch_number::text else 'Batch' end as batch_name,
  s.started_by,
  p.full_name as started_by_name,
  s.started_at,
  s.closed_at,
  s.status,
  (s.started_at at time zone 'Asia/Dhaka')::date as session_date,
  to_char(s.started_at at time zone 'Asia/Dhaka', 'HH12:MI AM') as session_time,
  count(ar.id) filter (where ar.id is not null) as total_present
from classmate.attendance_sessions s
join classmate.semester_courses sc on sc.id = s.semester_course_id
join classmate.courses c on c.id = sc.course_id
left join classmate.batches b on b.id = s.batch_id
left join classmate.profiles p on p.id = s.started_by
left join classmate.attendance_records ar on ar.session_id = s.id
group by s.id, sc.code_override, c.course_code, sc.title_override, c.course_title,
         s.batch_id, b.batch_number, s.started_by, p.full_name, s.started_at, s.closed_at, s.status;

-- 6. Unified View: Course-wise Student Attendance & Collegiate Eligibility
create or replace view classmate.v_course_student_summary as
with course_sessions as (
  select
    s.semester_course_id,
    s.batch_id,
    count(distinct s.id) filter (where s.status in ('closed', 'open')) as total_sessions
  from classmate.attendance_sessions s
  group by s.semester_course_id, s.batch_id
),
student_presence as (
  select
    s.semester_course_id,
    ar.student_id,
    count(distinct ar.session_id) as attended_sessions
  from classmate.attendance_records ar
  join classmate.attendance_sessions s on s.id = ar.session_id
  group by s.semester_course_id, ar.student_id
)
select
  sc.id as semester_course_id,
  coalesce(sc.code_override, c.course_code) as course_code,
  coalesce(sc.title_override, c.course_title) as course_title,
  cs.batch_id,
  stu.id as student_id,
  stu.student_id as student_roll,
  stu.full_name as student_name,
  coalesce(cs.total_sessions, 0) as total_classes_held,
  coalesce(sp.attended_sessions, 0) as classes_attended,
  case
    when coalesce(cs.total_sessions, 0) = 0 then 100.0
    else round((coalesce(sp.attended_sessions, 0)::numeric / cs.total_sessions::numeric) * 100.0, 1)
  end as attendance_percentage,
  case
    when coalesce(cs.total_sessions, 0) = 0 then 'Collegiate'
    when (coalesce(sp.attended_sessions, 0)::numeric / cs.total_sessions::numeric) >= 0.75 then 'Collegiate'
    when (coalesce(sp.attended_sessions, 0)::numeric / cs.total_sessions::numeric) >= 0.60 then 'Non-Collegiate'
    else 'Discollegiate'
  end as eligibility_status
from course_sessions cs
join classmate.semester_courses sc on sc.id = cs.semester_course_id
join classmate.courses c on c.id = sc.course_id
join classmate.profiles stu on stu.batch_id = cs.batch_id and stu.role = 'student'
left join student_presence sp on sp.semester_course_id = sc.id and sp.student_id = stu.id;

-- 7. RPC: Student Course-Wise Attendance Overview
create or replace function classmate.get_student_course_attendance_overview(p_student_id uuid default null)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  target_id uuid := coalesce(p_student_id, auth.uid());
  result jsonb;
begin
  if target_id is null then
    raise exception 'Target student not identified' using errcode='42501';
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'semester_course_id', v.semester_course_id,
        'course_code', v.course_code,
        'course_title', v.course_title,
        'total_classes_held', v.total_classes_held,
        'classes_attended', v.classes_attended,
        'attendance_percentage', v.attendance_percentage,
        'eligibility_status', v.eligibility_status
      ) order by v.course_code
    ),
    '[]'::jsonb
  )
  into result
  from classmate.v_course_student_summary v
  where v.student_id = target_id;

  return result;
end;
$$;

-- Permissions
grant select on classmate.v_course_attendance_sessions to authenticated;
grant select on classmate.v_course_student_summary to authenticated;
grant execute on function classmate.get_student_course_attendance_overview(uuid) to authenticated;

commit;
