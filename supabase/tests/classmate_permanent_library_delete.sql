begin;
do $$
declare d uuid; b uuid; s uuid; c uuid; sc uuid; owner_id uuid;
  resource uuid; linked_notice_id uuid;
begin
  select p.id into owner_id from classmate.profiles p
    join classmate.app_owners o on o.email = p.email where o.active limit 1;
  if owner_id is null then raise exception 'Test owner is missing'; end if;
  insert into classmate.departments(name,code,is_active)
    values ('QA Permanent Delete', 'qapdelete', true) returning id into d;
  insert into classmate.batches(department_id,batch_number,academic_session)
    values (d,1,1) returning id into b;
  select id into s from classmate.semesters where batch_id=b and semester_number=1;
  update classmate.semesters set status='active' where id=s;
  insert into classmate.courses(department_id,course_code,course_title,course_type)
    values (d,'QADEL','Delete Test','theory') returning id into c;
  insert into classmate.semester_courses(semester_id,course_id)
    values (s,c) returning id into sc;
  insert into classmate.file_metadata(department_id,batch_id,semester_course_id,
    uploaded_by,title,file_type,r2_object_key,mime_type,size_bytes,status,category)
  values (d,b,sc,owner_id,'QA Delete','pdf','qa/delete/' || gen_random_uuid(),
    'application/pdf',123,'pending','notes') returning id into resource;
  update classmate.file_metadata set status='active' where id=resource;
  select id into linked_notice_id from classmate.notices where resource_id=resource;
  if linked_notice_id is null then raise exception 'Linked notice missing'; end if;
  insert into classmate.file_favorites(profile_id,file_id) values (owner_id,resource);
  insert into classmate.notice_reads(notice_id,profile_id) values (linked_notice_id,owner_id);
  update classmate.file_metadata set status='deleting' where id=resource;
  if not classmate.finalize_resource_deletion(resource) then
    raise exception 'Deletion failed';
  end if;
  if classmate.finalize_resource_deletion(resource) then
    raise exception 'Repeated deletion should report already gone';
  end if;
  if exists (select 1 from classmate.file_metadata where id=resource)
    or exists (select 1 from classmate.notices where id=linked_notice_id)
    or exists (select 1 from classmate.notice_reads where notice_id=linked_notice_id)
    or exists (select 1 from classmate.file_favorites where file_id=resource)
    or exists (select 1 from classmate.notification_outbox
      where (kind='notice' and record_id=linked_notice_id)
        or (kind='file' and record_id=resource)) then
    raise exception 'Permanent deletion left related data';
  end if;
end $$;
set local role authenticated;
do $$ begin
  begin
    perform classmate.finalize_resource_deletion(gen_random_uuid());
    raise exception 'Authenticated user could call server-only deletion';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
