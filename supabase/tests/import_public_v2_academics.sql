-- One-time additive import after the global owner signs in to Supabase Auth.
-- Run against production only after reviewing the preflight counts. This keeps
-- every public V2 row and rolls back completely if a mapping is ambiguous.
begin;

do $$
begin
  if (select count(*) from classmate.profiles where role = 'admin'
      and verification_status = 'active') <> 1 then
    raise exception 'A confirmed Supabase Auth owner profile is required';
  end if;
  if exists (select 1 from classmate.batches)
    or exists (select 1 from classmate.courses)
    or exists (select 1 from classmate.notices)
    or exists (select 1 from classmate.routine_slots) then
    raise exception 'Target academic tables are not empty; inspect before importing';
  end if;
  if exists (
    select 1 from public.batches old_batch
    join public.departments old_dept on old_dept.id = old_batch.department_id
    left join classmate.departments new_dept on new_dept.code = lower(old_dept.code)
    where new_dept.id is null or new_dept.session_offset is null
       or old_batch.cohort_code !~ '^[0-9]{1,2}$'
       or old_batch.admission_year % 100 < 0
       or old_batch.admission_year % 100 > 99
       or old_batch.cohort_code::integer <> old_batch.admission_year % 100
          - new_dept.session_offset
  ) then
    raise exception 'Legacy batch cannot be mapped to department/session formula';
  end if;
  if exists (
    select 1 from public.batch_semesters bs
    join public.semesters old_semester on old_semester.id = bs.semester_id
    where old_semester.ordinal not between 1 and 8
  ) then
    raise exception 'Legacy semester ordinal is outside 1..8';
  end if;
  if exists (
    select 1 from public.batch_semesters bs
    where bs.state::text = 'published'
    group by bs.batch_id having count(*) > 1
  ) then
    raise exception 'More than one published semester exists for a legacy batch';
  end if;
  if exists (
    select 1 from public.routine_slots slot
    where slot.deleted_at is null and slot.weekday not between 0 and 6
  ) then
    raise exception 'Legacy routine weekday is outside 0..6';
  end if;
  if exists (
    select 1 from public.notices notice
    where notice.state::text = 'published' and notice.deleted_at is null
      and ((select count(*) from public.notice_targets target
        where target.notice_id = notice.id) <> 1
        or (select count(*) from public.notice_targets target
          where target.notice_id = notice.id and target.batch_id is not null) <> 1)
  ) then
    raise exception 'A published legacy notice lacks exactly one batch target';
  end if;
  if exists (
    select 1 from public.notices notice
    join public.profiles old_author on old_author.id = notice.author_id
    left join classmate.profiles new_author
      on new_author.email = lower(old_author.email::text)
    where notice.state::text = 'published' and notice.deleted_at is null
      and new_author.id is null
  ) then
    raise exception 'A published notice author has no Supabase Auth profile';
  end if;
end $$;

insert into classmate.batches
  (id, department_id, batch_number, academic_session, is_active, created_at)
select old_batch.id, new_dept.id, old_batch.cohort_code::smallint,
  (old_batch.admission_year % 100)::smallint, not old_batch.is_archived,
  old_batch.created_at
from public.batches old_batch
join public.departments old_dept on old_dept.id = old_batch.department_id
join classmate.departments new_dept on new_dept.code = lower(old_dept.code);

insert into classmate.courses
  (id, department_id, course_code, course_title, course_type, created_at)
select old_course.id, new_dept.id, upper(trim(old_course.code)),
  trim(old_course.name),
  case lower(old_course.kind::text)
    when 'lab' then 'lab' when 'theory' then 'theory' else 'other' end,
  old_course.created_at
from public.courses old_course
join public.departments old_dept on old_dept.id = old_course.department_id
join classmate.departments new_dept on new_dept.code = lower(old_dept.code);

update classmate.semesters new_semester set status = 'completed'
from public.batch_semesters bs
join public.semesters old_semester on old_semester.id = bs.semester_id
where bs.state::text = 'published' and new_semester.batch_id = bs.batch_id
  and new_semester.semester_number < old_semester.ordinal;

update classmate.semesters new_semester set status = 'active',
  published_at = coalesce(bs.published_at, now())
from public.batch_semesters bs
join public.semesters old_semester on old_semester.id = bs.semester_id
where bs.state::text = 'published' and new_semester.batch_id = bs.batch_id
  and new_semester.semester_number = old_semester.ordinal;

insert into classmate.semester_courses (id, semester_id, course_id, created_at)
select offering.id, new_semester.id, offering.course_id, offering.created_at
from public.course_offerings offering
join public.batch_semesters bs on bs.id = offering.batch_semester_id
join public.semesters old_semester on old_semester.id = bs.semester_id
join classmate.semesters new_semester on new_semester.batch_id = bs.batch_id
  and new_semester.semester_number = old_semester.ordinal
where bs.state::text = 'published' and offering.is_active;

insert into classmate.routine_slots
  (id, semester_course_id, day_of_week, start_time, end_time, room, type, created_at)
select slot.id, slot.course_offering_id, slot.weekday, slot.starts_at,
  slot.ends_at, slot.room, coalesce(slot.class_kind, 'class'), slot.created_at
from public.routine_slots slot
join classmate.semester_courses new_course on new_course.id = slot.course_offering_id
where slot.deleted_at is null;

insert into classmate.notices
  (id, department_id, batch_id, semester_course_id, author_id,
   title, body, published_at)
select notice.id, new_batch.department_id, new_batch.id,
  target.course_offering_id, new_author.id, notice.title,
  coalesce(notice.body, ''), coalesce(notice.published_at, notice.created_at)
from public.notices notice
join public.notice_targets target on target.notice_id = notice.id
join classmate.batches new_batch on new_batch.id = target.batch_id
join public.profiles old_author on old_author.id = notice.author_id
join classmate.profiles new_author
  on new_author.email = lower(old_author.email::text)
where notice.state::text = 'published' and notice.deleted_at is null;

-- Historical notices must not send fresh FCM notifications during import.
delete from classmate.notification_outbox
where kind = 'notice' and record_id in
  (select id from public.notices);

do $$
begin
  if (select count(*) from classmate.batches) <> (select count(*) from public.batches)
    or (select count(*) from classmate.courses) <> (select count(*) from public.courses)
    or (select count(*) from classmate.routine_slots) <>
      (select count(*) from public.routine_slots slot
       join classmate.semester_courses sc on sc.id = slot.course_offering_id
       where slot.deleted_at is null)
    or (select count(*) from classmate.notices) <>
      (select count(*) from public.notices notice
       where notice.state::text = 'published' and notice.deleted_at is null) then
    raise exception 'Imported academic counts do not match source counts';
  end if;
end $$;

insert into classmate.audit_log (actor_id, action, details)
select id, 'import_public_v2_academics', jsonb_build_object(
  'batches', (select count(*) from classmate.batches),
  'courses', (select count(*) from classmate.courses),
  'semester_courses', (select count(*) from classmate.semester_courses),
  'routine_slots', (select count(*) from classmate.routine_slots),
  'notices', (select count(*) from classmate.notices))
from classmate.profiles where role = 'admin';

commit;
