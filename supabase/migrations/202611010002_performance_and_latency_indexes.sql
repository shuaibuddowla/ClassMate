begin;

-- 1. Index for fast student attendance lookup and historical queries
create index if not exists attendance_records_student_marked_idx
  on classmate.attendance_records(student_id, marked_at desc);

-- 2. Index for fast session status lookups
create index if not exists attendance_sessions_status_started_idx
  on classmate.attendance_sessions(status, started_at desc);

-- 3. Index for routine slot lookups by semester course and day
create index if not exists routine_slots_course_day_idx
  on classmate.routine_slots(semester_course_id, day_of_week);

-- 4. Index for fast notice batch feeds
create index if not exists notices_batch_created_idx
  on classmate.notices(batch_id, created_at desc);

commit;
