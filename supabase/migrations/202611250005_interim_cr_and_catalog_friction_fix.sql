-- Migration: 202611250005_interim_cr_and_catalog_friction_fix.sql
-- Description:
--   1. Add is_interim_cr flag on classmate.profiles; auto_assign_first_batch_cr grants a 14-day
--      interim CR role (is_interim_cr = true) that clears automatically on official CR assignment/transfer.
--   2. Upgrade classmate.add_batch_course to resolve existing department course codes via
--      semester_courses overrides (title_override, type_override, credit_override) instead of throwing
--      a catalog conflict error.

BEGIN;

-- 1. Interim CR tracking
ALTER TABLE classmate.profiles
  ADD COLUMN IF NOT EXISTS is_interim_cr boolean NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION classmate.auto_assign_first_batch_cr()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
  if new.role='student' and new.verification_status='active' and new.batch_id is not null then
    perform pg_advisory_xact_lock(hashtextextended(new.batch_id::text, 0));
    if not exists(select 1 from classmate.profiles p where p.batch_id=new.batch_id
      and p.is_cr and p.cr_batch_id=new.batch_id and p.verification_status='active'
      and (p.cr_valid_until is null or p.cr_valid_until>now())) then
      update classmate.profiles
      set is_cr=true,
          is_interim_cr=true,
          cr_batch_id=new.batch_id,
          cr_valid_until=now() + interval '14 days',
          updated_at=now()
      where id=new.id;
    end if;
  end if;
  return new;
end;
$function$;

CREATE OR REPLACE FUNCTION classmate.assign_cr(target_profile uuid, target_batch uuid, valid_until timestamp with time zone DEFAULT NULL::timestamp with time zone)
 RETURNS classmate.profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare p classmate.profiles%rowtype;
begin
  perform classmate.require_owner();
  if valid_until is not null and valid_until <= now() then raise exception 'Expiry must be in future'; end if;
  update classmate.profiles
  set is_cr = true,
      is_interim_cr = false,
      cr_batch_id = target_batch,
      cr_valid_until = valid_until,
      updated_at = now()
  where id = target_profile and role = 'student' and verification_status = 'active'
    and batch_id = target_batch returning * into p;
  if p.id is null then raise exception 'Active student does not belong to batch'; end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'assign_cr', p.id);
  return p;
end;
$function$;

CREATE OR REPLACE FUNCTION classmate.set_batch_cr(target_profile uuid, target_active boolean DEFAULT true)
 RETURNS classmate.profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare caller classmate.profiles%rowtype; target classmate.profiles%rowtype;
begin
  select * into caller from classmate.profiles where id=auth.uid() and verification_status='active';
  select * into target from classmate.profiles where id=target_profile and role='student'
    and verification_status='active' for update;
  if caller.id is null or target.id is null then raise exception 'Active caller and student required'; end if;
  if caller.role <> 'admin' and not (caller.role='student' and caller.is_cr
    and caller.batch_id=caller.cr_batch_id and caller.cr_batch_id=target.batch_id
    and (caller.cr_valid_until is null or caller.cr_valid_until>now())) then
    raise exception 'Batch CR or admin permission required' using errcode='42501';
  end if;
  if caller.role <> 'admin' and not classmate.is_active() then
    raise exception 'Caller account is inactive' using errcode='42501';
  end if;
  update classmate.profiles
  set is_cr=target_active,
      is_interim_cr=false,
      cr_batch_id=case when target_active then target.batch_id else null end,
      cr_valid_until=null,
      updated_at=now()
  where id=target.id returning * into target;
  insert into classmate.audit_log(actor_id,action,target_id)
    values(auth.uid(),case when target_active then 'appoint_batch_cr' else 'remove_batch_cr' end,target.id);
  return target;
end;
$function$;

CREATE OR REPLACE FUNCTION classmate.transfer_batch_cr(target_profile uuid)
 RETURNS classmate.profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare caller classmate.profiles%rowtype; target classmate.profiles%rowtype;
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  select * into caller from classmate.profiles where id=auth.uid() and role='student'
    and verification_status='active' and is_cr and cr_batch_id=batch_id and cr_batch_id is not null
    and (cr_valid_until is null or cr_valid_until>now()) for update;
  if caller.id is null then raise exception 'Active batch CR required' using errcode='42501'; end if;
  select * into target from classmate.profiles where id=target_profile and role='student'
    and verification_status='active' and batch_id=caller.batch_id for update;
  if target.id is null then raise exception 'Target must be an active student in your batch'; end if;
  update classmate.profiles
  set is_cr=false, is_interim_cr=false, cr_batch_id=null, cr_valid_until=null, updated_at=now()
  where id=caller.id;
  update classmate.profiles
  set is_cr=true, is_interim_cr=false, cr_batch_id=caller.batch_id, cr_valid_until=null, updated_at=now()
  where id=target.id returning * into target;
  insert into classmate.audit_log(actor_id,action,target_id)
    values(caller.id,'transfer_batch_cr',target.id);
  return target;
end;
$function$;

-- 2. Fix add_batch_course catalog conflict friction via semester_courses overrides
CREATE OR REPLACE FUNCTION classmate.add_batch_course(target_batch uuid, target_code text, target_title text, target_type text, target_credit numeric DEFAULT NULL::numeric)
 RETURNS classmate.semester_courses
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  b classmate.batches%rowtype;
  active_semester uuid;
  course classmate.courses%rowtype;
  offering classmate.semester_courses%rowtype;
  actor classmate.profiles%rowtype;
  v_code text := upper(trim(target_code));
  v_title text := trim(target_title);
begin
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management permission denied' using errcode = '42501';
  end if;
  select * into b from classmate.batches where id=target_batch and is_active for update;
  if b.id is null then raise exception 'Running batch not found'; end if;
  select s.id into active_semester from classmate.semesters s
    where s.batch_id=target_batch and s.status='active';
  if active_semester is null then
    raise exception 'Publish a semester for this batch before adding courses';
  end if;
  if nullif(v_code,'') is null or nullif(v_title,'') is null
    or target_type not in ('theory','lab','other') then
    raise exception 'Course code, title, and type are required';
  end if;

  select * into course from classmate.courses c
    where c.department_id=b.department_id and c.course_code=v_code;

  if course.id is null then
    insert into classmate.courses(department_id,course_code,course_title,course_type,credit)
    values (b.department_id,v_code,v_title,target_type,target_credit)
    returning * into course;
  end if;

  insert into classmate.semester_courses(
    semester_id, course_id, title_override, type_override, credit_override
  )
  values (
    active_semester,
    course.id,
    nullif(v_title, course.course_title),
    nullif(target_type, course.course_type),
    case when target_credit is not null and target_credit is distinct from course.credit then target_credit else null end
  )
  on conflict (semester_id, course_id) do update set
    title_override = coalesce(excluded.title_override, classmate.semester_courses.title_override),
    type_override = coalesce(excluded.type_override, classmate.semester_courses.type_override),
    credit_override = coalesce(excluded.credit_override, classmate.semester_courses.credit_override)
  returning * into offering;

  select * into actor from classmate.profiles where id=auth.uid();
  if actor.role='teacher' then
    insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
    values (actor.id,offering.id,actor.id)
    on conflict (teacher_id,semester_course_id) do update set active=true,
      assigned_by=excluded.assigned_by;
  end if;
  insert into classmate.audit_log(actor_id,action,target_id)
    values (auth.uid(),'add_batch_course',offering.id);
  return offering;
end;
$function$;

NOTIFY pgrst, 'reload schema';

COMMIT;
