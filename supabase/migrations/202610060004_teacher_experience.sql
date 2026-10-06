begin;
create or replace function classmate.batch_timetable_catalog(target_batch uuid)
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


revoke all on function classmate.batch_timetable_catalog(uuid) from public,anon;
grant execute on function classmate.batch_timetable_catalog(uuid) to authenticated;
create or replace function classmate.batch_friends(target_batch uuid,query_text text default '',result_offset integer default 0)
returns table(profile_id uuid,full_name text,student_id text,role text,is_cr boolean,avatar_url text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'This batch directory is unavailable' using errcode='42501'; end if;
  if result_offset<0 or result_offset is null or length(query_text)>100 then raise exception 'Invalid directory query' using errcode='22023'; end if;
  return query select p.id,p.full_name,p.student_id,p.role::text,p.is_cr,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    from classmate.profiles p join auth.users u on u.id=p.id
    where p.verification_status='active' and ((p.role='student' and p.batch_id=target_batch and p.department_id=(select department_id from classmate.batches where id=target_batch)) or (p.role='teacher' and classmate.batch_directory_member(target_batch,p.id)) or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.active and o.email=p.email)))
      and position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')))>0
    order by (p.role='admin') desc,p.full_name,p.id limit 100 offset result_offset;
end $$;
-- Deliberately expose only the developer's public academic identity.
create or replace function classmate.developer_profile() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'full_name', p.full_name, 'student_id', coalesce(p.student_id,case when p.email ~ '^[a-z]+[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$' then upper(split_part(p.email,'@',1)) end),
    'department', d.name, 'department_code', d.code,
    'batch_number', b.batch_number, 'academic_session', b.academic_session,
    'avatar_url', case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') else null end)
  from classmate.profiles p
  join classmate.app_owners o on o.email=p.email and o.active
  join auth.users u on u.id=p.id
  left join lateral (select x.* from classmate.departments x where x.id=p.department_id or (p.department_id is null and x.email_prefix=substring(p.email from '^([a-z]+)[0-9]')) order by (x.id=p.department_id) desc nulls last limit 1) d on true
  left join lateral (select x.* from classmate.batches x where x.id=p.batch_id or (p.batch_id is null and x.department_id=d.id and x.academic_session=substring(p.email from '^[a-z]+([0-9]{2})')::integer and x.batch_number=x.academic_session-d.session_offset) order by (x.id=p.batch_id) desc nulls last limit 1) b on true
  where auth.uid() is not null
  order by p.id limit 1;
$$;
revoke all on function classmate.developer_profile() from public,anon;
grant execute on function classmate.developer_profile() to authenticated;

notify pgrst,'reload schema';
commit;
