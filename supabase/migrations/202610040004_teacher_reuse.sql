begin;
-- Merge only unambiguous names in the same department; preserve course links and real accounts.
do $merge$
declare g record; keep_id uuid; item record;
begin
  for g in select department_id,lower(regexp_replace(trim(full_name),'\s+',' ','g')) name
    from classmate.teacher_directory group by 1,2
    having count(*)>1 and count(distinct lower(email))<=1 and count(distinct profile_id)<=1
  loop
    select id into keep_id from classmate.teacher_directory where department_id=g.department_id
      and lower(regexp_replace(trim(full_name),'\s+',' ','g'))=g.name
      order by (profile_id is not null) desc,(email is not null) desc,created_at,id limit 1;
    for item in select * from classmate.teacher_directory where department_id=g.department_id
      and lower(regexp_replace(trim(full_name),'\s+',' ','g'))=g.name and id<>keep_id
    loop
      update classmate.teacher_directory set email=coalesce(email,item.email),profile_id=coalesce(profile_id,item.profile_id) where id=keep_id;
      update classmate.semester_courses set teacher_record_id=keep_id where teacher_record_id=item.id;
      delete from classmate.teacher_directory where id=item.id;
    end loop;
  end loop;
end $merge$;
CREATE OR REPLACE FUNCTION classmate.save_batch_course(target_batch uuid, target_offering uuid, target_code text, target_title text, target_teacher_name text, target_teacher_record uuid DEFAULT NULL::uuid, target_credit numeric DEFAULT NULL::numeric, target_catalog_course uuid DEFAULT NULL::uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare sem uuid; dept uuid; offering classmate.semester_courses%rowtype;
  catalog_id uuid; teacher_id uuid; inferred_type text; code_value text:=upper(trim(target_code));
begin
  if not classmate.can_manage_batch_catalog(target_batch) then
    raise exception 'Course management denied' using errcode='42501'; end if;
  select b.department_id,s.id into dept,sem from classmate.batches b
    join classmate.semesters s on s.batch_id=b.id and s.status='active'
    where b.id=target_batch and b.is_active for update of s;
  if sem is null then raise exception 'Ask your administrator to publish an active semester first'; end if;
  if length(code_value) not between 2 and 30 or code_value is null
    or length(trim(target_title)) not between 2 and 200 or target_title is null then
    raise exception 'Enter a course code and name'; end if;
  if target_credit is not null and (target_credit<=0 or target_credit>30) then
    raise exception 'Credit must be between 0 and 30'; end if;
  inferred_type:=case when trim(target_title) ~* 'lab$' then 'lab' else 'theory' end;
  if target_offering is not null then
    if not classmate.is_owner() and exists(select 1 from classmate.profiles where id=auth.uid() and role='teacher') then
      raise exception 'Only the admin or batch CR can edit course details' using errcode='42501'; end if;
    select * into offering from classmate.semester_courses where id=target_offering and semester_id=sem for update;
    if not found then raise exception 'Course does not belong to this active batch' using errcode='42501'; end if;
  end if;
  if exists(select 1 from classmate.semester_courses sc join classmate.courses c on c.id=sc.course_id
    where sc.semester_id=sem and sc.id is distinct from target_offering
      and upper(coalesce(sc.code_override,c.course_code))=code_value) then
    raise exception 'This course code is already in the batch'; end if;
  if target_teacher_record is not null then
    select id into teacher_id from classmate.teacher_directory where id=target_teacher_record and department_id=dept;
    -- Only a teacher already visible in this batch can be reused by a CR.
    if teacher_id is null or (not classmate.is_owner() and not exists(select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id=sc.semester_id where sc.teacher_record_id=teacher_id and s.batch_id=target_batch)) then
      raise exception 'Teacher record is unavailable' using errcode='42501'; end if;
  elsif nullif(trim(target_teacher_name),'') is not null then
    if length(trim(target_teacher_name)) not between 2 and 200 then raise exception 'Enter a valid teacher name'; end if;
    -- Serialize matching names within a department, including concurrent course saves.
    perform pg_advisory_xact_lock(hashtextextended(dept::text || lower(regexp_replace(trim(target_teacher_name),'\s+',' ','g')),0));
    select id into teacher_id from classmate.teacher_directory
      where department_id=dept and lower(regexp_replace(trim(full_name),'\s+',' ','g'))=
        lower(regexp_replace(trim(target_teacher_name),'\s+',' ','g'))
      order by (profile_id is not null) desc,(email is not null) desc,created_at,id limit 1;
    if teacher_id is null then
      insert into classmate.teacher_directory(department_id,full_name,created_by)
        values(dept,trim(target_teacher_name),auth.uid()) returning id into teacher_id;
    end if;
  end if;
  if target_offering is null then
    if target_catalog_course is not null then
      select id into catalog_id from classmate.courses where id=target_catalog_course and department_id=dept;
      if catalog_id is null then raise exception 'Course belongs to a different department'; end if;
    else
      insert into classmate.courses(department_id,course_code,course_title,course_type,credit)
        values(dept,code_value,trim(target_title),inferred_type,target_credit)
        on conflict(department_id,course_code) do nothing;
      select id into catalog_id from classmate.courses where department_id=dept and course_code=code_value;
    end if;
    if exists(select 1 from classmate.semester_courses where semester_id=sem and course_id=catalog_id) then
      raise exception 'This shared course is already in the batch'; end if;
    insert into classmate.semester_courses(semester_id,course_id) values(sem,catalog_id) returning * into offering;
    if exists(select 1 from classmate.profiles where id=auth.uid() and role='teacher') then
      insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
        values(auth.uid(),offering.id,auth.uid()) on conflict(teacher_id,semester_course_id) do update set active=true;
    end if;
  end if;
  -- Choosing a draft name must never assign or revoke a real teacher account.
  update classmate.semester_courses sc set
    code_override=case when code_value=c.course_code then null else code_value end,
    title_override=case when trim(target_title)=c.course_title then null else trim(target_title) end,
    type_override=case when inferred_type=c.course_type then null else inferred_type end,
    credit_override=case when target_credit is not distinct from c.credit then null else target_credit end,
    teacher_record_id=teacher_id from classmate.courses c where sc.id=offering.id and c.id=sc.course_id;
  update classmate.routine_slots set type=case when inferred_type='lab' then 'lab' else 'class' end
    where semester_course_id=offering.id;
  insert into classmate.audit_log(actor_id,action,target_id)
    values(auth.uid(),'save_batch_course',offering.id);
  return jsonb_build_object('id',offering.id,'course_id',offering.course_id);
end $function$;

create or replace function classmate.owner_create_teacher(target_department uuid,target_name text,target_email text)
returns classmate.teacher_directory language plpgsql security definer set search_path='' as $$
declare t classmate.teacher_directory%rowtype;
begin
  perform classmate.require_owner();
  perform pg_advisory_xact_lock(hashtextextended(target_department::text || lower(regexp_replace(trim(target_name),'\s+',' ','g')),0));
  select * into t from classmate.teacher_directory where department_id=target_department
    and lower(regexp_replace(trim(full_name),'\s+',' ','g'))=lower(regexp_replace(trim(target_name),'\s+',' ','g'))
    order by (profile_id is not null) desc,(email is not null) desc,created_at,id limit 1;
  if t.id is null then
    insert into classmate.teacher_directory(department_id,full_name,created_by)
      values(target_department,trim(target_name),auth.uid()) returning * into t;
  elsif t.email is not null and nullif(trim(target_email),'') is not null and lower(t.email)<>lower(trim(target_email)) then
    raise exception 'A teacher with this name has a different email; edit the existing profile';
  end if;
  return classmate.owner_save_teacher(t.id,target_name,coalesce(nullif(trim(target_email),''),t.email));
end $$;
commit;
