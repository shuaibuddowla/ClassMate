-- Transactional staging/production verification; no file or notice persists.
begin;
do $$
declare d uuid; b uuid; s uuid; c uuid; sc uuid; owner_id uuid; resource uuid;
begin
  select p.id into owner_id from classmate.profiles p
    join classmate.app_owners o on o.email = p.email where o.active limit 1;
  insert into classmate.departments(name,code,is_active)
    values ('QA Library', 'qalibrary', true) returning id into d;
  insert into classmate.batches(department_id,batch_number,academic_session)
    values (d,1,1) returning id into b;
  select id into s from classmate.semesters where batch_id = b and semester_number = 1;
  update classmate.semesters set status = 'active' where id = s;
  insert into classmate.courses(department_id,course_code,course_title,course_type)
    values (d,'QALIB','Library Test','theory') returning id into c;
  insert into classmate.semester_courses(semester_id,course_id)
    values (s,c) returning id into sc;
  insert into classmate.file_metadata(department_id,batch_id,semester_course_id,
    uploaded_by,title,file_type,r2_object_key,mime_type,size_bytes,status,category)
  values (d,b,sc,owner_id,'QA Notes','pdf','qa/library/test-' || gen_random_uuid(),
    'application/pdf',123,'pending','notes') returning id into resource;
  update classmate.file_metadata set status='active' where id=resource;
  if (select count(*) from classmate.notices where resource_id=resource) <> 1 then
    raise exception 'Upload did not create one resource notice';
  end if;
  if (select count(*) from classmate.notification_outbox o
      where (o.kind='notice' and o.record_id in
        (select n.id from classmate.notices n where n.resource_id=resource))
        or (o.kind='file' and o.record_id=resource)) <> 1 then
    raise exception 'Upload queued duplicate notifications';
  end if;
  perform set_config('classmate.qa_resource_id',resource::text,true);
end $$;
select set_config('request.jwt.claim.sub', u.id::text, true)
from auth.users u join classmate.app_owners o on lower(u.email) = o.email
where o.active and u.email_confirmed_at is not null limit 1;
set local role authenticated;
do $$
declare target_notice_id uuid; original_user uuid := auth.uid();
begin
  select n.id into target_notice_id from classmate.notices n
    where n.resource_id=current_setting('classmate.qa_resource_id')::uuid;
  perform classmate.mark_notices_read(array[target_notice_id]);
  perform classmate.mark_notices_read(array[target_notice_id]);
  if not exists (select 1 from classmate.notice_read_counts(array[target_notice_id]) c
    where c.notice_id=target_notice_id and c.read_count=1 and c.read_by_me) then
    raise exception 'Read receipt count is wrong';
  end if;
  if (select count(*) from classmate.notice_readers(target_notice_id)) <> 1 then
    raise exception 'Reader list is wrong';
  end if;
  perform set_config('request.jwt.claim.sub',
    '00000000-0000-0000-0000-000000000000', true);
  begin
    perform count(*) from classmate.notice_readers(target_notice_id);
    raise exception 'Unauthorized reader list was available';
  exception when insufficient_privilege then null;
  end;
  perform set_config('request.jwt.claim.sub', original_user::text, true);
end $$;
reset role;
do $$
declare resource uuid := current_setting('classmate.qa_resource_id')::uuid;
begin
  update classmate.file_metadata set status='deleting' where id=resource;
  update classmate.file_metadata set status='archived' where id=resource;
  if exists (select 1 from classmate.notices where resource_id=resource) then
    raise exception 'Archived file left a notice behind';
  end if;
end $$;
rollback;
