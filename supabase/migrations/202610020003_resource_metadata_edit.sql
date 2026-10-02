begin;
create function classmate.edit_resource_metadata(target_resource uuid, target_title text, target_category text, target_course uuid)
returns classmate.file_metadata language plpgsql security definer set search_path = '' as $$
declare resource classmate.file_metadata; updated classmate.file_metadata;
begin
  select * into resource from classmate.file_metadata where id=target_resource and status='active' for update;
  if not found then raise exception 'File is no longer available' using errcode='P0002'; end if;
  if not (classmate.is_owner() or exists (
    select 1 from classmate.profiles p join classmate.batches b on b.id=p.cr_batch_id
    where p.id=(select auth.uid()) and p.role='student' and p.verification_status='active'
      and p.is_cr and p.cr_batch_id=resource.batch_id and b.is_active
      and (p.cr_valid_until is null or p.cr_valid_until>now())
  )) then raise exception 'Only the owner or an active CR of this batch can edit files' using errcode='42501'; end if;
  if target_title is null or length(trim(target_title)) not between 1 and 200 then
    raise exception 'File name must contain 1 to 200 characters' using errcode='23514'; end if;
  if target_category is null or target_category not in ('notes','slides','questions','syllabus','other') then
    raise exception 'Choose a valid category' using errcode='23514'; end if;
  if target_course is null or not exists (
    select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id join classmate.batches b on b.id=s.batch_id
    where sc.id=target_course and s.batch_id=resource.batch_id and b.department_id=resource.department_id
      and (s.status='active' or sc.id=resource.semester_course_id)
  ) then raise exception 'Choose a subject in this batch' using errcode='23514'; end if;
  update classmate.file_metadata set title=trim(target_title),category=target_category,semester_course_id=target_course
    where id=resource.id returning * into updated;
  update classmate.notices set title=left('Resource: '||updated.title,200),semester_course_id=updated.semester_course_id
    where resource_id=resource.id;
  insert into classmate.audit_log(actor_id,action,target_id) values ((select auth.uid()),'edit_resource_metadata',resource.id);
  return updated;
end;
$$;
revoke all on function classmate.edit_resource_metadata(uuid,text,text,uuid) from public,anon;
grant execute on function classmate.edit_resource_metadata(uuid,text,text,uuid) to authenticated;
notify pgrst, 'reload schema';
commit;
