begin;

-- Insert course offerings through a single checked server-side operation.
-- This keeps owner/admin authorization explicit while returning the row needed
-- by the Android UI, without relying on PostgREST INSERT RETURNING RLS.
create or replace function public.create_v2_course_offering(
  target_batch_semester uuid,
  target_course uuid,
  target_section uuid default null
)
returns public.course_offerings
language plpgsql
security definer
set search_path = ''
as $$
declare
  selected public.course_offerings%rowtype;
  selected_batch uuid;
  selected_department uuid;
  course_department uuid;
begin
  if not public.is_active_user() then
    raise exception 'An active ClassMate profile is required';
  end if;

  select bs.batch_id, b.department_id, c.department_id
    into selected_batch, selected_department, course_department
  from public.batch_semesters bs
  join public.batches b on b.id = bs.batch_id
  join public.courses c on c.id = target_course
  where bs.id = target_batch_semester;

  if selected_batch is null then
    raise exception 'Batch semester or course not found';
  end if;
  if selected_department is distinct from course_department then
    raise exception 'Course must belong to the selected batch department';
  end if;
  if target_section is not null and not exists (
    select 1 from public.sections s
    where s.id = target_section and s.batch_id = selected_batch
  ) then
    raise exception 'Section must belong to the selected batch';
  end if;
  if not public.can_admin_academic_scope(
    selected_department, selected_batch, target_section, null
  ) then
    raise exception 'Not authorized to add a course to this batch semester';
  end if;

  select o.* into selected
  from public.course_offerings o
  where o.batch_semester_id = target_batch_semester
    and o.course_id = target_course
    and o.section_id is not distinct from target_section
  limit 1;
  if selected.id is not null then
    return selected;
  end if;

  insert into public.course_offerings (batch_semester_id, course_id, section_id)
  values (target_batch_semester, target_course, target_section)
  on conflict do nothing
  returning * into selected;

  if selected.id is null then
    select o.* into selected
    from public.course_offerings o
    where o.batch_semester_id = target_batch_semester
      and o.course_id = target_course
      and o.section_id is not distinct from target_section
    limit 1;
  end if;
  if selected.id is null then
    raise exception 'Course offering could not be created';
  end if;
  return selected;
end;
$$;

revoke all on function public.create_v2_course_offering(uuid, uuid, uuid) from public, anon;
grant execute on function public.create_v2_course_offering(uuid, uuid, uuid) to authenticated;

commit;
