begin;

comment on column public.routine_slots.weekday is
  'PostgreSQL DOW convention: 0=Sunday, 1=Monday, ..., 6=Saturday';
comment on column public.bus_schedules.weekdays is
  'PostgreSQL DOW convention: 0=Sunday, 1=Monday, ..., 6=Saturday';

-- Keep related academic records in the same university/department/batch. These
-- checks run in PostgreSQL so a modified Android client cannot bypass them.
create or replace function public.validate_batch_semester_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  batch_university uuid;
  semester_university uuid;
begin
  select d.university_id into batch_university
  from public.batches b
  join public.departments d on d.id = b.department_id
  where b.id = new.batch_id;

  select s.university_id into semester_university
  from public.semesters s
  where s.id = new.semester_id;

  if batch_university is distinct from semester_university then
    raise exception 'Batch and semester must belong to the same university';
  end if;
  return new;
end;
$$;

create trigger batch_semesters_validate_scope
  before insert or update on public.batch_semesters
  for each row execute procedure public.validate_batch_semester_scope();

create or replace function public.validate_course_offering_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  offering_batch uuid;
  offering_department uuid;
  course_department uuid;
  section_batch uuid;
begin
  select bs.batch_id, b.department_id
    into offering_batch, offering_department
  from public.batch_semesters bs
  join public.batches b on b.id = bs.batch_id
  where bs.id = new.batch_semester_id;

  select c.department_id into course_department
  from public.courses c
  where c.id = new.course_id;

  if offering_department is distinct from course_department then
    raise exception 'Course offering must use a course from the batch department';
  end if;

  if new.section_id is not null then
    select s.batch_id into section_batch
    from public.sections s
    where s.id = new.section_id;

    if offering_batch is distinct from section_batch then
      raise exception 'Course offering section must belong to the offering batch';
    end if;
  end if;
  return new;
end;
$$;

create trigger course_offerings_validate_scope
  before insert or update on public.course_offerings
  for each row execute procedure public.validate_course_offering_scope();

create or replace function public.validate_student_profile_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  batch_department uuid;
  section_batch uuid;
begin
  if new.assigned_batch_id is not null then
    select b.department_id into batch_department
    from public.batches b
    where b.id = new.assigned_batch_id;

    if new.department_id is distinct from batch_department then
      raise exception 'Assigned batch must belong to the student department';
    end if;
  end if;

  if new.section_id is not null then
    select s.batch_id into section_batch
    from public.sections s
    where s.id = new.section_id;

    if new.assigned_batch_id is null or section_batch is distinct from new.assigned_batch_id then
      raise exception 'Assigned section must belong to the assigned batch';
    end if;
  end if;
  return new;
end;
$$;

create trigger student_profiles_validate_scope
  before insert or update on public.student_profiles
  for each row execute procedure public.validate_student_profile_scope();

create or replace function public.validate_role_grant_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  resolved_department uuid;
  resolved_batch uuid;
  resolved_section uuid;
  resolved_university uuid;
  profile_university uuid;
begin
  if new.role = 'teacher' then
    if new.course_offering_id is null
       or new.department_id is not null
       or new.batch_id is not null
       or new.section_id is not null then
      raise exception 'Teacher grants must target only one course offering';
    end if;
  elsif new.role = 'cr' then
    if new.batch_id is null or new.course_offering_id is not null then
      raise exception 'CR grants require a batch and cannot target an offering';
    end if;
  elsif new.role = 'admin' and new.course_offering_id is not null then
    raise exception 'Admin grants cannot target a course offering';
  end if;

  if new.course_offering_id is not null then
    select b.department_id, bs.batch_id, o.section_id, d.university_id
      into resolved_department, resolved_batch, resolved_section, resolved_university
    from public.course_offerings o
    join public.batch_semesters bs on bs.id = o.batch_semester_id
    join public.batches b on b.id = bs.batch_id
    join public.departments d on d.id = b.department_id
    where o.id = new.course_offering_id;
  elsif new.section_id is not null then
    select b.department_id, s.batch_id, s.id, d.university_id
      into resolved_department, resolved_batch, resolved_section, resolved_university
    from public.sections s
    join public.batches b on b.id = s.batch_id
    join public.departments d on d.id = b.department_id
    where s.id = new.section_id;
  elsif new.batch_id is not null then
    select b.department_id, b.id, d.university_id
      into resolved_department, resolved_batch, resolved_university
    from public.batches b
    join public.departments d on d.id = b.department_id
    where b.id = new.batch_id;
  elsif new.department_id is not null then
    select d.id, d.university_id
      into resolved_department, resolved_university
    from public.departments d
    where d.id = new.department_id;
  end if;

  if new.department_id is not null
     and new.department_id is distinct from resolved_department then
    raise exception 'Role department does not match its narrower scope';
  end if;
  if new.batch_id is not null and new.batch_id is distinct from resolved_batch then
    raise exception 'Role batch does not match its narrower scope';
  end if;
  if new.section_id is not null and new.section_id is distinct from resolved_section then
    raise exception 'Role section does not match its narrower scope';
  end if;

  select p.university_id into profile_university
  from public.profiles p where p.id = new.profile_id;
  if resolved_university is not null
     and profile_university is distinct from resolved_university then
    raise exception 'Role scope must belong to the profile university';
  end if;
  return new;
end;
$$;

create trigger role_grants_validate_scope
  before insert or update on public.role_grants
  for each row execute procedure public.validate_role_grant_scope();

-- Admin CRUD remains protected by RLS. University and semester creation require
-- a global admin; lower academic records may be managed by a scoped admin.
create policy "global admins create universities"
  on public.universities for insert to authenticated
  with check (public.has_active_role('admin', null, null, null, null));
create policy "global admins update universities"
  on public.universities for update to authenticated
  using (public.has_active_role('admin', null, null, null, null))
  with check (public.has_active_role('admin', null, null, null, null));

create policy "global admins create departments"
  on public.departments for insert to authenticated
  with check (public.has_active_role('admin', null, null, null, null));
create policy "scoped admins update departments"
  on public.departments for update to authenticated
  using (public.can_admin_academic_scope(id, null, null, null))
  with check (public.can_admin_academic_scope(id, null, null, null));

create policy "global admins create semesters"
  on public.semesters for insert to authenticated
  with check (public.has_active_role('admin', null, null, null, null));
create policy "global admins update semesters"
  on public.semesters for update to authenticated
  using (public.has_active_role('admin', null, null, null, null))
  with check (public.has_active_role('admin', null, null, null, null));

create policy "scoped admins create batches"
  on public.batches for insert to authenticated
  with check (public.can_admin_academic_scope(department_id, null, null, null));
create policy "scoped admins update batches"
  on public.batches for update to authenticated
  using (public.can_admin_academic_scope(department_id, id, null, null))
  with check (public.can_admin_academic_scope(department_id, id, null, null));

create policy "scoped admins create sections"
  on public.sections for insert to authenticated
  with check (public.can_admin_academic_scope(
    null, batch_id, null, null
  ));
create policy "scoped admins update sections"
  on public.sections for update to authenticated
  using (public.can_admin_academic_scope(null, batch_id, id, null))
  with check (public.can_admin_academic_scope(null, batch_id, id, null));

create policy "scoped admins create batch semesters"
  on public.batch_semesters for insert to authenticated
  with check (public.can_admin_academic_scope(null, batch_id, null, null));
create policy "scoped admins update batch semesters"
  on public.batch_semesters for update to authenticated
  using (public.can_admin_academic_scope(null, batch_id, null, null))
  with check (public.can_admin_academic_scope(null, batch_id, null, null));

create policy "scoped admins create courses"
  on public.courses for insert to authenticated
  with check (public.can_admin_academic_scope(department_id, null, null, null));
create policy "scoped admins update courses"
  on public.courses for update to authenticated
  using (public.can_admin_academic_scope(department_id, null, null, null))
  with check (public.can_admin_academic_scope(department_id, null, null, null));

create policy "scoped admins create offerings"
  on public.course_offerings for insert to authenticated
  with check (exists (
    select 1
    from public.batch_semesters bs
    join public.batches b on b.id = bs.batch_id
    where bs.id = batch_semester_id
      and public.can_admin_academic_scope(b.department_id, b.id, section_id, null)
  ));
create policy "scoped admins update offerings"
  on public.course_offerings for update to authenticated
  using (public.can_admin_academic_scope(null, null, null, id))
  with check (public.can_admin_academic_scope(null, null, null, id));

-- Publish a batch semester atomically and archive any previously published one.
create or replace function public.publish_semester(target_batch_semester uuid)
returns public.batch_semesters
language plpgsql
security definer
set search_path = ''
as $$
declare
  selected public.batch_semesters%rowtype;
  selected_department uuid;
begin
  select bs.* into selected
  from public.batch_semesters bs
  where bs.id = target_batch_semester
  for update;

  if selected.id is null then
    raise exception 'Batch semester not found';
  end if;
  select b.department_id into selected_department
  from public.batches b
  where b.id = selected.batch_id
  for update;
  if not public.can_admin_academic_scope(
    selected_department, selected.batch_id, null, null
  ) then
    raise exception 'Not authorized to publish this semester';
  end if;

  update public.batch_semesters
  set state = 'archived'
  where batch_id = selected.batch_id
    and id <> selected.id
    and state = 'published';

  update public.batch_semesters
  set state = 'published', published_at = now()
  where id = selected.id
  returning * into selected;

  update public.batches
  set active_batch_semester_id = selected.id
  where id = selected.batch_id;

  return selected;
end;
$$;

-- Clone offerings and routine slots into a new draft semester for the same batch.
create or replace function public.clone_semester(
  source_batch_semester uuid,
  target_semester uuid,
  target_starts_on date default null,
  target_ends_on date default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  source_row public.batch_semesters%rowtype;
  source_department uuid;
  new_batch_semester uuid;
  source_offering record;
  new_offering uuid;
begin
  select bs.* into source_row
  from public.batch_semesters bs
  where bs.id = source_batch_semester;

  if source_row.id is null then
    raise exception 'Source batch semester not found';
  end if;
  select b.department_id into source_department
  from public.batches b
  where b.id = source_row.batch_id;
  if not public.can_admin_academic_scope(
    source_department, source_row.batch_id, null, null
  ) then
    raise exception 'Not authorized to clone this semester';
  end if;

  insert into public.batch_semesters (
    batch_id, semester_id, state, starts_on, ends_on
  ) values (
    source_row.batch_id, target_semester, 'draft', target_starts_on, target_ends_on
  ) returning id into new_batch_semester;

  for source_offering in
    select * from public.course_offerings
    where batch_semester_id = source_row.id
  loop
    insert into public.course_offerings (
      batch_semester_id, course_id, section_id
    ) values (
      new_batch_semester, source_offering.course_id, source_offering.section_id
    ) returning id into new_offering;

    insert into public.routine_slots (
      course_offering_id, weekday, starts_at, ends_at, room, class_kind, created_by
    )
    select
      new_offering, weekday, starts_at, ends_at, room, class_kind, public.current_profile_id()
    from public.routine_slots
    where course_offering_id = source_offering.id and deleted_at is null;
  end loop;

  return new_batch_semester;
end;
$$;

-- Staff allowlist changes are only exposed through this global-admin function.
create or replace function public.set_staff_allowlist_entry(
  target_email text,
  target_university uuid,
  target_role public.app_role,
  target_department uuid default null,
  target_is_active boolean default true,
  target_notes text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  entry_id uuid;
begin
  if not public.has_active_role('admin', null, null, null, null) then
    raise exception 'Global administrator access required';
  end if;
  if target_role not in ('teacher', 'admin') then
    raise exception 'Staff allowlist role must be teacher or admin';
  end if;
  if lower(target_email) !~ '^[^@]+@[^@]+$' then
    raise exception 'A valid email address is required';
  end if;
  if target_department is not null and not exists (
    select 1 from public.departments d
    where d.id = target_department and d.university_id = target_university
  ) then
    raise exception 'Staff department must belong to the selected university';
  end if;

  insert into public.staff_allowlist (
    university_id, email, role, department_id, is_active, notes, created_by
  ) values (
    target_university, lower(target_email)::citext, target_role,
    target_department, target_is_active, target_notes, public.current_profile_id()
  )
  on conflict (email) do update set
    university_id = excluded.university_id,
    role = excluded.role,
    department_id = excluded.department_id,
    is_active = excluded.is_active,
    notes = excluded.notes
  returning id into entry_id;

  return entry_id;
end;
$$;

create or replace function public.grant_scoped_role(
  target_profile uuid,
  target_role public.app_role,
  target_department uuid default null,
  target_batch uuid default null,
  target_section uuid default null,
  target_offering uuid default null,
  target_expires_at timestamptz default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  grant_id uuid;
begin
  if target_role = 'student' then
    raise exception 'Student access is derived from student_profiles';
  end if;
  if target_role = 'cr' and (target_batch is null or target_offering is not null) then
    raise exception 'CR grants require a batch and cannot target an offering';
  end if;
  if target_role = 'teacher' and target_offering is null then
    raise exception 'Teacher grants require a course offering';
  end if;
  if target_role = 'admin'
     and not public.has_active_role('admin', null, null, null, null) then
    raise exception 'Only a global administrator can grant admin access';
  end if;
  if not public.can_admin_academic_scope(
    target_department, target_batch, target_section, target_offering
  ) then
    raise exception 'Not authorized to grant this scope';
  end if;
  if target_expires_at is not null and target_expires_at <= now() then
    raise exception 'Role expiry must be in the future';
  end if;

  insert into public.role_grants (
    profile_id, role, department_id, batch_id, section_id,
    course_offering_id, expires_at, granted_by
  ) values (
    target_profile, target_role, target_department, target_batch, target_section,
    target_offering, target_expires_at, public.current_profile_id()
  ) returning id into grant_id;

  return grant_id;
end;
$$;

create or replace function public.revoke_role(target_grant uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing public.role_grants%rowtype;
begin
  select * into existing from public.role_grants where id = target_grant;
  if existing.id is null then
    raise exception 'Role grant not found';
  end if;
  if existing.role = 'admin'
     and not public.has_active_role('admin', null, null, null, null) then
    raise exception 'Only a global administrator can revoke admin access';
  end if;
  if not public.can_admin_academic_scope(
    existing.department_id, existing.batch_id, existing.section_id,
    existing.course_offering_id
  ) then
    raise exception 'Not authorized to revoke this role';
  end if;

  update public.role_grants
  set revoked_at = now()
  where id = existing.id and revoked_at is null;
end;
$$;

create or replace function public.override_student_assignment(
  target_profile uuid,
  target_batch uuid,
  target_section uuid default null
)
returns public.student_profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_department uuid;
  updated public.student_profiles%rowtype;
begin
  select b.department_id into target_department
  from public.batches b where b.id = target_batch;

  if target_department is null then
    raise exception 'Target batch not found';
  end if;
  if not public.can_admin_academic_scope(
    target_department, target_batch, target_section, null
  ) then
    raise exception 'Not authorized to override this student assignment';
  end if;

  update public.student_profiles
  set department_id = target_department,
      assigned_batch_id = target_batch,
      section_id = target_section,
      assignment_overridden = true
  where profile_id = target_profile
  returning * into updated;

  if updated.profile_id is null then
    raise exception 'Student profile not found';
  end if;

  update public.profiles set status = 'active' where id = target_profile;
  return updated;
end;
$$;

-- A reusable append-only audit trigger for administrative and shared records.
create or replace function public.write_audit_log()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  row_id uuid;
begin
  if tg_op = 'DELETE' then
    row_id := (to_jsonb(old) ->> 'id')::uuid;
  else
    row_id := (to_jsonb(new) ->> 'id')::uuid;
  end if;
  insert into public.audit_log (actor_id, action, entity_table, entity_id, old_data, new_data)
  values (
    public.current_profile_id(), lower(tg_op), tg_table_name, row_id,
    case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) end,
    case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) end
  );
  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create trigger audit_departments after insert or update or delete on public.departments
  for each row execute procedure public.write_audit_log();
create trigger audit_batches after insert or update or delete on public.batches
  for each row execute procedure public.write_audit_log();
create trigger audit_sections after insert or update or delete on public.sections
  for each row execute procedure public.write_audit_log();
create trigger audit_batch_semesters after insert or update or delete on public.batch_semesters
  for each row execute procedure public.write_audit_log();
create trigger audit_courses after insert or update or delete on public.courses
  for each row execute procedure public.write_audit_log();
create trigger audit_course_offerings after insert or update or delete on public.course_offerings
  for each row execute procedure public.write_audit_log();
create trigger audit_role_grants after insert or update or delete on public.role_grants
  for each row execute procedure public.write_audit_log();
create trigger audit_routine_slots after insert or update or delete on public.routine_slots
  for each row execute procedure public.write_audit_log();
create trigger audit_notices after insert or update or delete on public.notices
  for each row execute procedure public.write_audit_log();
create trigger audit_class_changes after insert or update or delete on public.class_changes
  for each row execute procedure public.write_audit_log();
create trigger audit_bus_schedules after insert or update or delete on public.bus_schedules
  for each row execute procedure public.write_audit_log();
create trigger audit_resources after insert or update or delete on public.resources
  for each row execute procedure public.write_audit_log();

revoke execute on function public.set_updated_at() from public, anon, authenticated;
revoke execute on function public.validate_batch_semester_scope() from public, anon, authenticated;
revoke execute on function public.validate_course_offering_scope() from public, anon, authenticated;
revoke execute on function public.validate_student_profile_scope() from public, anon, authenticated;
revoke execute on function public.validate_role_grant_scope() from public, anon, authenticated;
revoke execute on function public.write_audit_log() from public, anon, authenticated;

revoke execute on function public.publish_semester(uuid) from public, anon;
revoke execute on function public.clone_semester(uuid, uuid, date, date) from public, anon;
revoke execute on function public.set_staff_allowlist_entry(text, uuid, public.app_role, uuid, boolean, text) from public, anon;
revoke execute on function public.grant_scoped_role(uuid, public.app_role, uuid, uuid, uuid, uuid, timestamptz) from public, anon;
revoke execute on function public.revoke_role(uuid) from public, anon;
revoke execute on function public.override_student_assignment(uuid, uuid, uuid) from public, anon;

grant execute on function public.publish_semester(uuid) to authenticated;
grant execute on function public.clone_semester(uuid, uuid, date, date) to authenticated;
grant execute on function public.set_staff_allowlist_entry(text, uuid, public.app_role, uuid, boolean, text) to authenticated;
grant execute on function public.grant_scoped_role(uuid, public.app_role, uuid, uuid, uuid, uuid, timestamptz) to authenticated;
grant execute on function public.revoke_role(uuid) to authenticated;
grant execute on function public.override_student_assignment(uuid, uuid, uuid) to authenticated;

commit;
