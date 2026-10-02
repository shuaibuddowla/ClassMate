begin;
create function classmate.edit_course(target_course uuid, target_code text, target_title text)
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
  update classmate.courses set course_code=code_value,course_title=title_value
    where id=target_course returning * into updated;
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
revoke all on function classmate.edit_course(uuid,text,text) from public,anon;
grant execute on function classmate.edit_course(uuid,text,text) to authenticated;
notify pgrst, 'reload schema';
commit;
