begin;

-- ============================================================================
-- 1. can_manage_batch_catalog: Allow Admins, Owners, and active CRs
-- ============================================================================
create or replace function classmate.can_manage_batch_catalog(target_batch uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p
    where p.id = (select auth.uid()) and p.verification_status = 'active'
      and (
        p.role = 'admin'
        or (p.role = 'student' and p.is_cr
            and (p.cr_batch_id = target_batch or p.batch_id = target_batch)
            and (p.cr_valid_until is null or p.cr_valid_until > now()))
        or (p.role = 'teacher' and exists (
          select 1 from classmate.teacher_allowlist a
          join classmate.batches b on b.department_id = a.department_id
          where a.email = p.email and a.active and b.id = target_batch
            and classmate.can_read_batch(target_batch)))
      )
  );
$$;

revoke all on function classmate.can_manage_batch_catalog(uuid) from public, anon;
grant execute on function classmate.can_manage_batch_catalog(uuid) to authenticated;

-- ============================================================================
-- 2. remove_batch_course: Complete cascade delete of batch courses for CRs/Admins
-- ============================================================================
create or replace function classmate.remove_batch_course(target_offering uuid)
returns void language plpgsql security definer set search_path = '' as $$
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

  -- 1. Clean up file metadata & favorites/reads
  delete from classmate.file_favorites where file_id in (
    select id from classmate.file_metadata where semester_course_id = target_offering
  );
  delete from classmate.file_reads where file_id in (
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

revoke all on function classmate.remove_batch_course(uuid) from public, anon;
grant execute on function classmate.remove_batch_course(uuid) to authenticated;

-- ============================================================================
-- 3. owner_save_teacher: Relax authorization for Admin & active CRs, support all valid emails
-- ============================================================================
create or replace function classmate.owner_save_teacher(target_record uuid, target_name text, target_email text default null)
returns classmate.teacher_directory language plpgsql security definer set search_path = '' as $$
declare
  t classmate.teacher_directory%rowtype;
  normalized text := nullif(lower(trim(target_email)), '');
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
      raise exception 'Teacher management permission denied' using errcode = '42501';
    end if;
  end if;

  if length(trim(target_name)) not between 2 and 200 or target_name is null then
    raise exception 'Teacher name is required';
  end if;

  if normalized is not null and normalized !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
    raise exception 'Enter a valid email address';
  end if;

  select * into t from classmate.teacher_directory where id = target_record for update;
  if not found then raise exception 'Teacher record not found'; end if;

  if normalized is not null and exists(select 1 from classmate.profiles where email = normalized and role <> 'teacher') then
    raise exception 'This email belongs to a non-teacher account';
  end if;

  if normalized is distinct from t.email and t.email is not null then
    perform classmate.set_teacher_allowlist(t.email, t.department_id, false);
  end if;

  if normalized is distinct from t.email and t.profile_id is not null then
    update classmate.teacher_course_assignments set active = false where teacher_id = t.profile_id
      and semester_course_id in (select id from classmate.semester_courses where teacher_record_id = t.id);
  end if;

  update classmate.teacher_directory set
    full_name = trim(target_name),
    email = normalized,
    profile_id = (select id from classmate.profiles where email = normalized and role = 'teacher'),
    updated_at = now()
  where id = t.id returning * into t;

  if normalized is not null then
    perform classmate.set_teacher_allowlist(normalized, t.department_id, true);
    if t.profile_id is not null then
      insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
        select t.profile_id, sc.id, auth.uid() from classmate.semester_courses sc where sc.teacher_record_id = t.id
        on conflict (teacher_id, semester_course_id) do update set active = true;
    end if;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id) values(auth.uid(), 'save_teacher_directory', t.id);
  return t;
end;
$$;

revoke all on function classmate.owner_save_teacher(uuid, text, text) from public, anon;
grant execute on function classmate.owner_save_teacher(uuid, text, text) to authenticated;

-- ============================================================================
-- 4. owner_create_teacher: Relax authorization for Admin & active CRs
-- ============================================================================
create or replace function classmate.owner_create_teacher(target_department uuid, target_name text, target_email text default null)
returns classmate.teacher_directory language plpgsql security definer set search_path = '' as $$
declare
  t classmate.teacher_directory%rowtype;
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
      raise exception 'Teacher creation permission denied' using errcode = '42501';
    end if;
  end if;

  perform pg_advisory_xact_lock(hashtextextended(target_department::text || lower(regexp_replace(trim(target_name),'\s+',' ','g')), 0));

  select * into t from classmate.teacher_directory where department_id = target_department
    and lower(regexp_replace(trim(full_name),'\s+',' ','g')) = lower(regexp_replace(trim(target_name),'\s+',' ','g'))
    order by (profile_id is not null) desc, (email is not null) desc, created_at, id limit 1;

  if t.id is null then
    insert into classmate.teacher_directory(department_id, full_name, created_by)
      values(target_department, trim(target_name), auth.uid()) returning * into t;
  elsif t.email is not null and nullif(trim(target_email),'') is not null and lower(t.email) <> lower(trim(target_email)) then
    raise exception 'A teacher with this name has a different email; edit the existing profile';
  end if;

  return classmate.owner_save_teacher(t.id, target_name, coalesce(nullif(trim(target_email),''), t.email));
end;
$$;

revoke all on function classmate.owner_create_teacher(uuid, text, text) from public, anon;
grant execute on function classmate.owner_create_teacher(uuid, text, text) to authenticated;

-- ============================================================================
-- 5. create_teacher synonym
-- ============================================================================
create or replace function classmate.create_teacher(target_department uuid, target_name text, target_email text default null)
returns classmate.teacher_directory language plpgsql security definer set search_path = '' as $$
begin
  return classmate.owner_create_teacher(target_department, target_name, target_email);
end;
$$;

revoke all on function classmate.create_teacher(uuid, text, text) from public, anon;
grant execute on function classmate.create_teacher(uuid, text, text) to authenticated;

notify pgrst, 'reload schema';

commit;
