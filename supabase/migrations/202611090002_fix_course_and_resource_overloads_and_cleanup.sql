-- ============================================================================
-- Migration: 202611090002_fix_course_and_resource_overloads_and_cleanup.sql
-- Description:
-- 1. Drop obsolete 8-parameter overload of classmate.save_batch_course which
--    caused PostgREST HTTP 300 (PGRST203: Could not choose best candidate) when
--    clients called save_batch_course without the extra parameters.
-- 2. Drop obsolete 4-parameter overload of classmate.edit_resource_metadata.
-- 3. Fix classmate.remove_batch_course to remove reference to non-existent
--    classmate.file_reads table.
-- ============================================================================

begin;

-- 1. Drop obsolete overloads
drop function if exists classmate.save_batch_course(uuid, uuid, text, text, text, uuid, numeric, uuid);
drop function if exists classmate.edit_resource_metadata(uuid, text, text, uuid);

-- 2. Update remove_batch_course
create or replace function classmate.remove_batch_course(target_offering uuid)
returns void
language plpgsql
security definer
set search_path to ''
as $$
declare
  offering classmate.semester_courses%rowtype;
  target_batch uuid;
begin
  select * into offering from classmate.semester_courses where id = target_offering for update;
  if offering.id is null then raise exception 'Course not found'; end if;

  select s.batch_id into target_batch from classmate.semesters s
    where s.id = offering.semester_id and s.status = 'active';
  if target_batch is null then raise exception 'Only active batch courses can be removed'; end if;

  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management permission denied' using errcode = '42501';
  end if;

  -- 1. Clean up file metadata & favorites
  delete from classmate.file_favorites where file_id in (
    select id from classmate.file_metadata where semester_course_id = target_offering
  );
  delete from classmate.file_metadata where semester_course_id = target_offering;

  -- 2. Clean up attendance sessions and records
  delete from classmate.attendance_records where session_id in (
    select id from classmate.attendance_sessions
    where semester_course_id = target_offering
       or routine_slot_id in (select id from classmate.routine_slots where semester_course_id = target_offering)
  );
  delete from classmate.attendance_sessions
  where semester_course_id = target_offering
     or routine_slot_id in (select id from classmate.routine_slots where semester_course_id = target_offering);

  -- 3. Clean up notifications & notices
  delete from classmate.notification_outbox o using classmate.notices n
    where n.semester_course_id = target_offering and o.kind = 'notice' and o.record_id = n.id;
  delete from classmate.notices where semester_course_id = target_offering;
  delete from classmate.notification_outbox where semester_course_id = target_offering;

  -- 4. Clean up class changes
  delete from classmate.notification_outbox o using classmate.class_changes c
    where c.semester_course_id = target_offering and o.kind = 'class_change' and o.record_id = c.id;
  delete from classmate.class_changes where semester_course_id = target_offering;

  -- 5. Clean up routine slots
  delete from classmate.routine_slots where semester_course_id = target_offering;

  -- 6. Clean up teacher assignments
  delete from classmate.course_teachers where semester_course_id = target_offering;
  delete from classmate.teacher_course_assignments where semester_course_id = target_offering;

  -- 7. Remove course offering
  delete from classmate.semester_courses where id = target_offering;

  insert into classmate.audit_log(actor_id, action, target_id)
    values (auth.uid(), 'remove_batch_course', target_offering);
end;
$$;

notify pgrst, 'reload schema';

commit;
