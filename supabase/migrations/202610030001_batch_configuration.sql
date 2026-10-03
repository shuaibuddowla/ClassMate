begin;

-- Draft teachers are directory records, never synthetic Auth users.
create table classmate.teacher_directory (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id),
  full_name text not null check(length(trim(full_name)) between 2 and 200),
  email text check(email = lower(trim(email)) and email like '%@mbstu.ac.bd'),
  profile_id uuid references classmate.profiles(id) on delete set null,
  created_by uuid not null references classmate.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(department_id,email)
);
alter table classmate.semester_courses
  add column title_override text,
  add column code_override text,
  add column type_override text check(type_override in ('theory','lab')),
  add column credit_override numeric check(credit_override>0 and credit_override<=30),
  add column teacher_record_id uuid references classmate.teacher_directory(id) on delete set null;
alter table classmate.teacher_directory enable row level security;
grant select on classmate.teacher_directory to authenticated;
grant all on classmate.teacher_directory to service_role;
create policy teacher_directory_read on classmate.teacher_directory for select to authenticated
using(classmate.is_owner() or exists(select 1 from classmate.semester_courses sc
  join classmate.semesters s on s.id=sc.semester_id
  where sc.teacher_record_id=teacher_directory.id and classmate.can_read_batch(s.batch_id)));

create function classmate.batch_course_catalog(target_batch uuid)
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
    order by coalesce(sc.code_override,c.course_code),sc.id;
end $$;

create function classmate.save_batch_course(target_batch uuid,target_offering uuid,
  target_code text,target_title text,target_teacher_name text,
  target_teacher_record uuid default null,target_credit numeric default null,
  target_catalog_course uuid default null)
returns jsonb language plpgsql security definer set search_path='' as $$
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
    insert into classmate.teacher_directory(department_id,full_name,created_by)
      values(dept,trim(target_teacher_name),auth.uid()) returning id into teacher_id;
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
end $$;

create function classmate.owner_save_teacher(target_record uuid,target_name text,target_email text)
returns classmate.teacher_directory language plpgsql security definer set search_path='' as $$
declare t classmate.teacher_directory%rowtype; normalized text:=nullif(lower(trim(target_email)),'');
begin
  perform classmate.require_owner();
  if length(trim(target_name)) not between 2 and 200 or target_name is null then raise exception 'Teacher name is required'; end if;
  if normalized is not null and normalized not like '%@mbstu.ac.bd' then raise exception 'Use a university email'; end if;
  select * into t from classmate.teacher_directory where id=target_record for update;
  if not found then raise exception 'Teacher record not found'; end if;
  if normalized is not null and exists(select 1 from classmate.profiles where email=normalized and role<>'teacher') then
    raise exception 'This email belongs to a non-teacher account'; end if;
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

create function classmate.link_registered_teacher() returns trigger
language plpgsql security definer set search_path='' as $$
begin
  if new.role='teacher' and new.verification_status='active' then
    update classmate.teacher_directory set profile_id=new.id,updated_at=now()
      where email=new.email and department_id=new.department_id;
    insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by)
      select new.id,sc.id,t.created_by from classmate.teacher_directory t
      join classmate.semester_courses sc on sc.teacher_record_id=t.id
      join classmate.teacher_allowlist a on a.email=t.email and a.department_id=t.department_id and a.active
      where t.profile_id=new.id
      on conflict(teacher_id,semester_course_id) do update set active=true;
  end if;
  return new;
end $$;
create trigger link_registered_teacher after insert or update of role,verification_status,department_id
  on classmate.profiles for each row execute function classmate.link_registered_teacher();

create or replace function classmate.timetable_details(target_batch uuid,target_date date,target_course_ids uuid[])
returns table(semester_course_id uuid,teacher_name text,cancelled boolean)
language sql stable security definer set search_path='' as $$
  select sc.id,coalesce(nullif(trim(t.full_name),''),
    (select nullif(trim(p.full_name),'') from classmate.teacher_course_assignments a
      join classmate.profiles p on p.id=a.teacher_id
      join classmate.teacher_allowlist al on al.email=p.email and al.department_id=p.department_id and al.active
      where a.semester_course_id=sc.id and a.active and p.role='teacher' and p.verification_status='active'
      order by a.created_at limit 1)),
    exists(select 1 from classmate.class_changes cc where cc.batch_id=target_batch
      and cc.semester_course_id=sc.id and cc.effective_date=target_date and cc.kind='cancelled')
  from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
  left join classmate.teacher_directory t on t.id=sc.teacher_record_id
  where sc.id=any(target_course_ids) and s.batch_id=target_batch and s.status='active'
    and classmate.can_read_batch(target_batch);
$$;

-- Preserve existing teacher assignments in the editable directory.
insert into classmate.teacher_directory(department_id,full_name,email,profile_id,created_by)
select distinct b.department_id,coalesce(nullif(trim(p.full_name),''),'Teacher'),p.email,p.id,a.assigned_by
from classmate.teacher_course_assignments a join classmate.profiles p on p.id=a.teacher_id
join classmate.semester_courses sc on sc.id=a.semester_course_id
join classmate.semesters s on s.id=sc.semester_id join classmate.batches b on b.id=s.batch_id
where p.role='teacher' on conflict(department_id,email) do nothing;
insert into classmate.teacher_directory(department_id,full_name,email,profile_id,created_by)
select a.department_id,coalesce(nullif(trim(p.full_name),''),'Teacher'),p.email,p.id,a.created_by
from classmate.profiles p join classmate.teacher_allowlist a on a.email=p.email
where p.role='teacher' on conflict(department_id,email) do nothing;
update classmate.semester_courses sc set teacher_record_id=t.id
from classmate.teacher_course_assignments a join classmate.profiles p on p.id=a.teacher_id
join classmate.teacher_directory t on t.profile_id=p.id
join classmate.semesters s on s.batch_id in(select id from classmate.batches where department_id=t.department_id)
where a.semester_course_id=sc.id and sc.semester_id=s.id and a.active and sc.teacher_record_id is null;

create function classmate.owner_teachers() returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
  perform classmate.require_owner();
  return coalesce((select jsonb_agg(row_value order by row_value->>'full_name') from (
    select jsonb_build_object('id',t.id,'full_name',t.full_name,'email',t.email,'profile_id',t.profile_id,'department_id',t.department_id,
      'course_count',count(sc.id),'course_names',coalesce(string_agg(distinct coalesce(sc.title_override,c.course_title),', '),'')) row_value
    from classmate.teacher_directory t left join classmate.semester_courses sc on sc.teacher_record_id=t.id
    left join classmate.courses c on c.id=sc.course_id group by t.id) q),'[]'::jsonb);
end $$;

create function classmate.owner_create_teacher(target_department uuid,target_name text,target_email text)
returns classmate.teacher_directory language plpgsql security definer set search_path='' as $$
declare t classmate.teacher_directory%rowtype;
begin
  perform classmate.require_owner();
  insert into classmate.teacher_directory(department_id,full_name,created_by)
    values(target_department,trim(target_name),auth.uid()) returning * into t;
  return classmate.owner_save_teacher(t.id,target_name,target_email);
end $$;

create function classmate.owner_assign_teacher_record(target_record uuid,target_offering uuid)
returns void language plpgsql security definer set search_path='' as $$
declare t classmate.teacher_directory%rowtype;
begin
  perform classmate.require_owner();
  select * into t from classmate.teacher_directory where id=target_record;
  if t.id is null or not exists(select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id=sc.semester_id join classmate.batches b on b.id=s.batch_id
    where sc.id=target_offering and b.department_id=t.department_id and s.status='active') then
    raise exception 'Choose an active course in the teacher department'; end if;
  update classmate.semester_courses set teacher_record_id=t.id where id=target_offering;
  if t.profile_id is not null and exists(select 1 from classmate.teacher_allowlist
    where email=t.email and department_id=t.department_id and active) then
    perform classmate.assign_teacher_to_course(t.profile_id,target_offering);
  end if;
  insert into classmate.audit_log(actor_id,action,target_id) values(auth.uid(),'assign_teacher_record',target_offering);
end $$;

create function classmate.owner_people(query_text text default '',result_offset integer default 0)
returns table(profile_id uuid,full_name text,student_id text,role text,is_cr boolean,
  avatar_url text,batch_id uuid,batch_name text,verification_status text,cr_valid_until timestamptz,profile_source text)
language plpgsql stable security definer set search_path='' as $$
begin
  perform classmate.require_owner();
  if result_offset is null or result_offset<0 or length(coalesce(query_text,''))>100 then raise exception 'Invalid directory query'; end if;
  return query select p.id,p.full_name,p.student_id,p.role::text,p.is_cr,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,
    p.batch_id,(upper(d.code)||' '||b.batch_number),p.verification_status::text,p.cr_valid_until,p.profile_source::text
    from classmate.profiles p join auth.users u on u.id=p.id left join classmate.batches b on b.id=p.batch_id left join classmate.departments d on d.id=b.department_id
    where position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')||' '||coalesce(upper(d.code)||' '||b.batch_number,'')))>0
    order by p.full_name,p.id limit 50 offset result_offset;
end $$;

revoke all on function classmate.batch_course_catalog(uuid),
  classmate.save_batch_course(uuid,uuid,text,text,text,uuid,numeric,uuid),
  classmate.owner_save_teacher(uuid,text,text),classmate.owner_people(text,integer),classmate.owner_teachers() from public,anon;
revoke all on function classmate.owner_create_teacher(uuid,text,text),classmate.owner_assign_teacher_record(uuid,uuid) from public,anon;
grant execute on function classmate.owner_create_teacher(uuid,text,text),classmate.owner_assign_teacher_record(uuid,uuid) to authenticated;
revoke all on function classmate.link_registered_teacher() from public,anon,authenticated;
grant execute on function classmate.batch_course_catalog(uuid),
  classmate.save_batch_course(uuid,uuid,text,text,text,uuid,numeric,uuid),
  classmate.owner_save_teacher(uuid,text,text),classmate.owner_people(text,integer),classmate.owner_teachers() to authenticated;
create or replace function classmate.post_cancellation_notice(
  target_batch uuid, target_course uuid, change_date date)
returns classmate.notices language plpgsql security definer set search_path = '' as $$
declare
  target_department uuid;
  course_title text;
  existing_notice classmate.notices%rowtype;
  change_row classmate.class_changes%rowtype;
begin
  if change_date not in ((now() at time zone 'Asia/Dhaka')::date,
      (now() at time zone 'Asia/Dhaka')::date + 1) then
    raise exception 'Choose today or tomorrow';
  end if;
  if target_course is null or not classmate.can_post(target_batch, target_course) then
    raise exception 'Course assignment required' using errcode = '42501';
  end if;
  select b.department_id, coalesce(sc.title_override,c.course_title) into target_department, course_title
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.batches b on b.id = s.batch_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_course and s.batch_id = target_batch and s.status = 'active';
  if target_department is null then
    raise exception 'Course semester is not active';
  end if;
  select n.* into existing_notice from classmate.notices n
  join classmate.class_changes cc on cc.id = n.class_change_id
  where cc.batch_id = target_batch and cc.semester_course_id = target_course
    and cc.effective_date = change_date and cc.kind = 'cancelled'
  order by n.published_at desc limit 1;
  if found then return existing_notice; end if;
  insert into classmate.class_changes(department_id, batch_id, semester_course_id,
    author_id, kind, effective_date, details)
  values (target_department, target_batch, target_course, auth.uid(),
    'cancelled', change_date, '') returning * into change_row;
  -- The notice below is the single notification for this action.
  delete from classmate.notification_outbox
    where kind = 'class_change' and record_id = change_row.id;
  insert into classmate.notices(department_id, batch_id, semester_course_id,
    author_id, title, body, class_change_id)
  values (target_department, target_batch, target_course, auth.uid(),
    left('Class cancelled · ' || course_title, 200),
    'Class on ' || to_char(change_date, 'DD Mon YYYY') || ' is cancelled.', change_row.id)
  returning * into existing_notice;
  return existing_notice;
end;
$$;


create or replace function classmate.clone_semester(from_id uuid, to_id uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare source classmate.semesters%rowtype; target classmate.semesters%rowtype;
  old_course record; new_course uuid;
begin
  perform classmate.require_owner();
  select * into source from classmate.semesters where id = from_id;
  select * into target from classmate.semesters where id = to_id for update;
  if source.id is null or target.id is null or source.id = target.id then raise exception 'Invalid semesters'; end if;
  if target.status <> 'not_started' then raise exception 'Target semester already published'; end if;
  if exists (select 1 from classmate.semester_courses where semester_id = target.id) then
    raise exception 'Target semester must be empty';
  end if;
  if exists (select 1 from classmate.semester_courses sc
      join classmate.courses c on c.id = sc.course_id
      join classmate.batches b on b.id = target.batch_id
      where sc.semester_id = source.id and c.department_id <> b.department_id) then
    raise exception 'Cross-department clone requires course mapping';
  end if;
  for old_course in select * from classmate.semester_courses where semester_id = source.id loop
    insert into classmate.semester_courses(semester_id,course_id,title_override,code_override,type_override,credit_override,teacher_record_id)
    values(target.id,old_course.course_id,old_course.title_override,old_course.code_override,old_course.type_override,old_course.credit_override,old_course.teacher_record_id) returning id into new_course;
    insert into classmate.routine_slots(semester_course_id, day_of_week, start_time, end_time, room, type)
    select new_course, day_of_week, start_time, end_time, room, type
    from classmate.routine_slots where semester_course_id = old_course.id;
    insert into classmate.teacher_course_assignments(teacher_id, semester_course_id, assigned_by)
    select teacher_id, new_course, auth.uid() from classmate.teacher_course_assignments
    where semester_course_id = old_course.id and active;
  end loop;
  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (auth.uid(), 'clone_semester', target.id, jsonb_build_object('from_id', source.id));
  return target.id;
end;
$$;

create or replace function classmate.save_routine_slot(target_id uuid, target_semester_course uuid,
  target_day smallint, target_start time, target_end time, target_room text)
returns classmate.routine_slots language plpgsql security definer set search_path = '' as $$
declare slot classmate.routine_slots%rowtype;
declare course_type text;
begin
  if not classmate.can_manage_routine(target_semester_course) then
    raise exception 'Routine permission denied' using errcode = '42501';
  end if;
  select coalesce(sc.type_override,c.course_type) into course_type
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  join classmate.courses c on c.id = sc.course_id
  where sc.id = target_semester_course and s.status in ('active', 'not_started');
  if course_type is null then raise exception 'Course is not available for routine editing'; end if;
  if target_day not between 0 and 6 or target_start >= target_end then
    raise exception 'Choose a valid day and time range';
  end if;
  if target_id is null then
    insert into classmate.routine_slots(semester_course_id, day_of_week,
      start_time, end_time, room, type)
    values (target_semester_course, target_day, target_start, target_end,
      nullif(trim(target_room), ''), case when course_type = 'lab' then 'lab' else 'class' end)
    returning * into slot;
  else
    if not exists (select 1 from classmate.routine_slots old
      where old.id = target_id and classmate.can_manage_routine(old.semester_course_id)) then
      raise exception 'Routine permission denied' using errcode = '42501';
    end if;
    update classmate.routine_slots set semester_course_id = target_semester_course,
      day_of_week = target_day, start_time = target_start, end_time = target_end,
      room = nullif(trim(target_room), ''),
      type = case when course_type = 'lab' then 'lab' else 'class' end
    where id = target_id returning * into slot;
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), case when target_id is null then 'add_routine_slot'
    else 'edit_routine_slot' end, slot.id);
  return slot;
end;
$$;


create or replace function classmate.edit_course(target_course uuid, target_code text, target_title text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare original classmate.courses%rowtype; updated classmate.courses%rowtype;
  code_value text := upper(trim(target_code)); title_value text := trim(target_title);
begin
  if not classmate.is_owner() or not exists(select 1 from classmate.profiles
      where id=auth.uid() and role='admin' and verification_status='active') then
    raise exception 'Only an active administrator can edit course details' using errcode='42501';
  end if;
  if code_value is null or length(code_value) not between 2 and 30 then
    raise exception 'Course code must contain 2 to 30 characters' using errcode='22023';
  end if;
  if title_value is null or length(title_value) not between 2 and 200 then
    raise exception 'Course name must contain 2 to 200 characters' using errcode='22023';
  end if;
  select * into original from classmate.courses where id=target_course for update;
  if not found then raise exception 'Course no longer exists' using errcode='22023'; end if;
  if exists(select 1 from classmate.courses where department_id=original.department_id
      and id<>target_course and upper(trim(course_code))=code_value) then
    raise exception 'This course code is already used in the department' using errcode='23505';
  end if;
  update classmate.courses set course_code=code_value,course_title=title_value,course_type=case when title_value ~* 'lab$' then 'lab' else 'theory' end
    where id=target_course returning * into updated;
  update classmate.routine_slots r set type=case when updated.course_type='lab' then 'lab' else 'class' end
    from classmate.semester_courses sc where r.semester_course_id=sc.id and sc.course_id=target_course and sc.type_override is null;
  insert into classmate.audit_log(actor_id,action,target_id,details)
    values(auth.uid(),'edit_course',target_course,jsonb_build_object(
      'old_code',original.course_code,'old_title',original.course_title,
      'new_code',updated.course_code,'new_title',updated.course_title));
  return jsonb_build_object('course',to_jsonb(updated),'offering_ids',
    (select coalesce(jsonb_agg(id),'[]'::jsonb) from classmate.semester_courses where course_id=target_course));
exception when unique_violation then
  raise exception 'This course code is already used in the department' using errcode='23505';
end;
$$;
notify pgrst,'reload schema';
commit;
