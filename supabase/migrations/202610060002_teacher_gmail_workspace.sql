begin;
-- Student/admin email rules remain institutional. Only owner-allowlisted teachers may use Gmail.
alter table classmate.profiles drop constraint profiles_email_check;
alter table classmate.profiles add constraint profiles_email_check check(email=lower(email) and email ~ '^[^[:space:]@]+@[^[:space:]@]+$' and (email like '%@mbstu.ac.bd' or (role='teacher' and email ~ '^[^[:space:]@]+@gmail[.]com$')));
alter table classmate.teacher_allowlist drop constraint teacher_allowlist_email_check;
alter table classmate.teacher_allowlist add constraint teacher_allowlist_email_check check(email=lower(trim(email)) and email ~ '^[^[:space:]@]+@(mbstu[.]ac[.]bd|gmail[.]com)$');
alter table classmate.teacher_directory drop constraint teacher_directory_email_check;
alter table classmate.teacher_directory add constraint teacher_directory_email_check check(email=lower(trim(email)) and email ~ '^[^[:space:]@]+@(mbstu[.]ac[.]bd|gmail[.]com)$');
create or replace function classmate.validate_scope() returns trigger language plpgsql
set search_path = '' as $$
declare d uuid; b uuid;
begin
  if tg_table_name = 'semester_courses' then
    select x.department_id into d from classmate.semesters s
      join classmate.batches x on x.id = s.batch_id where s.id = new.semester_id;
    if not exists (select 1 from classmate.courses c where c.id = new.course_id and c.department_id = d) then
      raise exception 'Course and semester department differ';
    end if;
  elsif tg_table_name = 'teacher_course_assignments' then
    select x.department_id into d from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id
      join classmate.batches x on x.id = s.batch_id where sc.id = new.semester_course_id;
    if new.active and not exists (select 1 from classmate.profiles p join classmate.teacher_allowlist a
      on a.email = p.email and a.department_id = d and a.active
      where p.id = new.teacher_id and p.role = 'teacher' and p.verification_status = 'active') then
      raise exception 'Teacher is not allowed for this department';
    end if;
  elsif tg_table_name in ('notices', 'file_metadata', 'class_changes')
    and new.semester_course_id is not null then
    select s.batch_id into b from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id where sc.id = new.semester_course_id;
    if b is distinct from new.batch_id then raise exception 'Course does not belong to batch'; end if;
  end if;
  return new;
end;
$$;

create or replace function classmate.create_or_initialize_profile() returns classmate.profiles
language plpgsql security definer set search_path = '' as $$
declare u auth.users%rowtype; result classmate.profiles%rowtype; dept uuid;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into u from auth.users where id = auth.uid();
  if u.id is null or u.email_confirmed_at is null
    or not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'google') then
    raise exception 'Confirmed Google account required' using errcode = '28000';
  end if;
  if lower(u.email) !~ '^[^@]+@mbstu[.]ac[.]bd$' and not (lower(u.email) ~ '^[^[:space:]@]+@gmail[.]com$' and exists(select 1 from classmate.teacher_allowlist where email=lower(u.email) and active)) then
    raise exception 'Use your university account or an admin-approved teacher Gmail' using errcode='28000';
  end if;
  if exists (select 1 from classmate.app_owners where email = lower(u.email) and active) then
    insert into classmate.profiles(id, email, full_name, role, verification_status)
    values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''), 'admin', 'active')
    on conflict (id) do update set role = 'admin', verification_status = 'active',
      department_id = null, batch_id = null, student_id = null, is_cr = false,
      cr_batch_id = null, cr_valid_until = null, updated_at = now();
  else
    select a.department_id into dept from classmate.teacher_allowlist a
      where a.email = lower(u.email) and a.active order by a.created_at limit 1;
    if dept is not null then
      insert into classmate.profiles(id, email, full_name, role, department_id, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'teacher', dept, 'active')
      on conflict (id) do update set role = 'teacher', department_id = excluded.department_id,
        batch_id = null, student_id = null, is_cr = false, cr_batch_id = null,
        cr_valid_until = null, verification_status = 'active', updated_at = now();
    else
      insert into classmate.profiles(id, email, full_name, role, verification_status)
      values (u.id, lower(u.email), coalesce(u.raw_user_meta_data->>'full_name', ''),
        'student', 'pending')
      on conflict (id) do update set role = 'student',
        verification_status = case when classmate.profiles.role <> 'student'
          then 'pending'::classmate.verification_status else classmate.profiles.verification_status end,
        department_id = case when classmate.profiles.role <> 'student'
          then null else classmate.profiles.department_id end,
        updated_at = now();
    end if;
  end if;
  select * into result from classmate.profiles where id = u.id;
  return result;
end;
$$;

create or replace function classmate.owner_save_teacher(target_record uuid,target_name text,target_email text)
returns classmate.teacher_directory language plpgsql security definer set search_path='' as $$
declare t classmate.teacher_directory%rowtype; normalized text:=nullif(lower(trim(target_email)),'');
begin
  perform classmate.require_owner();
  if length(trim(target_name)) not between 2 and 200 or target_name is null then raise exception 'Teacher name is required'; end if;
  if normalized is not null and normalized !~ '^[^[:space:]@]+@(mbstu[.]ac[.]bd|gmail[.]com)$' then raise exception 'Use a university email or Gmail'; end if;
  select * into t from classmate.teacher_directory where id=target_record for update;
  if not found then raise exception 'Teacher record not found'; end if;
  if normalized is not null and exists(select 1 from classmate.profiles where email=normalized and role<>'teacher') then
    raise exception 'This email belongs to a non-teacher account'; end if;
  if normalized is distinct from t.email and t.email is not null then
    perform classmate.set_teacher_allowlist(t.email,t.department_id,false);
  end if;
  if normalized is distinct from t.email and t.profile_id is not null then
    update classmate.teacher_course_assignments set active=false where teacher_id=t.profile_id
      and semester_course_id in(select id from classmate.semester_courses where teacher_record_id=t.id);
  end if;
  update classmate.teacher_directory set full_name=trim(target_name),email=normalized,
    profile_id=(select id from classmate.profiles where email=normalized and role='teacher'),updated_at=now()
    where id=t.id returning * into t;
  if normalized is not null then
    perform classmate.set_teacher_allowlist(normalized,t.department_id,true);
    if t.profile_id is not null then
      insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
        select t.profile_id,sc.id,auth.uid() from classmate.semester_courses sc where sc.teacher_record_id=t.id
        on conflict(teacher_id,semester_course_id) do update set active=true;
    end if;
  end if;
  insert into classmate.audit_log(actor_id,action,target_id) values(auth.uid(),'save_teacher_directory',t.id);
  return t;
end $$;


create function classmate.available_batches() returns jsonb language plpgsql stable security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501';end if;
 return coalesce((select jsonb_agg(to_jsonb(q)) from(select b.id,b.department_id,b.batch_number,b.academic_session,jsonb_build_object('code',d.code,'name',d.name) departments
 from classmate.batches b join classmate.departments d on d.id=b.department_id
 where b.is_active and classmate.can_read_batch(b.id) and (
 not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher') or exists(
 select 1 from classmate.teacher_course_assignments a join classmate.semester_courses sc on sc.id=a.semester_course_id join classmate.semesters s on s.id=sc.semester_id
 where a.teacher_id=auth.uid() and a.active and s.status='active' and s.batch_id=b.id))
 order by d.code,b.batch_number)q),'[]'::jsonb);
end $$;
revoke all on function classmate.available_batches() from public,anon;
grant execute on function classmate.available_batches() to authenticated;
-- Keep student/owner course catalogs unchanged; teachers see their assigned offerings.
create or replace function classmate.batch_course_catalog(target_batch uuid)
returns table(id uuid,offering_id uuid,course_code text,course_title text,course_type text,
  credit numeric,teacher_record_id uuid,teacher_name text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode='42501'; end if;
  return query select c.id,sc.id,coalesce(sc.code_override,c.course_code),
    coalesce(sc.title_override,c.course_title),coalesce(sc.type_override,c.course_type),coalesce(sc.credit_override,c.credit),
    sc.teacher_record_id,coalesce(t.full_name,'')
    from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
    join classmate.courses c on c.id=sc.course_id
    left join classmate.teacher_directory t on t.id=sc.teacher_record_id
    where s.batch_id=target_batch and s.status='active'
    and (not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher') or exists(select 1 from classmate.teacher_course_assignments a where a.teacher_id=auth.uid() and a.active and a.semester_course_id=sc.id))
    order by coalesce(sc.code_override,c.course_code),sc.id;
end $$;

notify pgrst,'reload schema';
commit;
