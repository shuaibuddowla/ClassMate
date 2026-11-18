begin;

-- ============================================================================
-- 1. Relax teacher_allowlist check constraint to allow any valid email
-- ============================================================================
alter table classmate.teacher_allowlist drop constraint if exists teacher_allowlist_email_check;
alter table classmate.teacher_allowlist add constraint teacher_allowlist_email_check
  check (email = lower(trim(email)) and email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$');

-- ============================================================================
-- 2. Relax cr_allowlist check constraint to allow any valid university email
-- ============================================================================
alter table classmate.cr_allowlist drop constraint if exists cr_allowlist_email_check;
alter table classmate.cr_allowlist add constraint cr_allowlist_email_check
  check (email = lower(trim(email)) and email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$');

-- ============================================================================
-- 3. Relax profiles check constraint to allow any valid email
-- ============================================================================
alter table classmate.profiles drop constraint if exists profiles_email_check;
alter table classmate.profiles add constraint profiles_email_check
  check (email = lower(trim(email)) and email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$');

-- ============================================================================
-- 4. Update set_teacher_allowlist: Allow Owners, Admins, and active CRs
-- ============================================================================
create or replace function classmate.set_teacher_allowlist(target_email text, target_department uuid, target_active boolean)
returns classmate.teacher_allowlist
language plpgsql
security definer
set search_path to ''
as $$
declare
  a classmate.teacher_allowlist%rowtype;
begin
  if not classmate.is_owner() then
    if not exists (
      select 1 from classmate.profiles p
      where p.id = auth.uid()
        and p.verification_status = 'active'
        and (
          p.role = 'admin'
          or (p.role = 'student' and p.is_cr and (p.cr_valid_until is null or p.cr_valid_until > now()))
        )
    ) then
      raise exception 'Teacher allowlist permission denied' using errcode = '42501';
    end if;
  end if;

  insert into classmate.teacher_allowlist(email, department_id, active, created_by)
  values (lower(trim(target_email)), target_department, target_active, auth.uid())
  on conflict (email, department_id) do update set active = excluded.active
  returning * into a;

  if not target_active then
    update classmate.teacher_course_assignments set active = false
    where teacher_id in (select id from classmate.profiles where email = a.email)
      and semester_course_id in (select sc.id from classmate.semester_courses sc
        join classmate.semesters s on s.id = sc.semester_id
        join classmate.batches b on b.id = s.batch_id
        where b.department_id = a.department_id);
  end if;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'set_teacher_allowlist', a.id);
  return a;
end;
$$;

revoke all on function classmate.set_teacher_allowlist(text, uuid, boolean) from public, anon;
grant execute on function classmate.set_teacher_allowlist(text, uuid, boolean) to authenticated;

-- ============================================================================
-- 5. Update owner_delete_teacher: Allow Owners, Admins, and active CRs
-- ============================================================================
create or replace function classmate.owner_delete_teacher(target_record uuid)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  teacher classmate.teacher_directory%rowtype;
  linked_courses integer;
begin
  if not classmate.is_owner() then
    if not exists (
      select 1 from classmate.profiles p
      where p.id = auth.uid()
        and p.verification_status = 'active'
        and (
          p.role = 'admin'
          or (p.role = 'student' and p.is_cr and (p.cr_valid_until is null or p.cr_valid_until > now()))
        )
    ) then
      raise exception 'Teacher deletion permission denied' using errcode = '42501';
    end if;
  end if;

  select * into teacher from classmate.teacher_directory where id = target_record for update;
  if not found then raise exception 'Teacher record not found'; end if;

  select count(*) into linked_courses from classmate.semester_courses
    where teacher_record_id = teacher.id;

  if teacher.email is not null then
    update classmate.teacher_allowlist set active = false
      where email = teacher.email and department_id = teacher.department_id and active;
  end if;

  if teacher.profile_id is not null then
    update classmate.teacher_course_assignments set active = false
      where teacher_id = teacher.profile_id and semester_course_id in
        (select id from classmate.semester_courses where teacher_record_id = teacher.id);
  end if;

  -- Clean up course_teachers junction table references
  delete from classmate.course_teachers where teacher_record_id = teacher.id;

  delete from classmate.teacher_directory where id = teacher.id;

  insert into classmate.audit_log(actor_id, action, target_id, details)
    values(auth.uid(), 'delete_teacher_directory', teacher.id,
      jsonb_build_object('name', teacher.full_name, 'unassigned_courses', linked_courses));

  return jsonb_build_object('deleted', true, 'unassigned_courses', linked_courses);
end;
$$;

revoke all on function classmate.owner_delete_teacher(uuid) from public, anon;
grant execute on function classmate.owner_delete_teacher(uuid) to authenticated;

notify pgrst, 'reload schema';

commit;
