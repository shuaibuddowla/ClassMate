begin;
do $$
declare owner_record record; batch_record record; expected boolean; actual boolean; checked integer:=0;
  fixture_department uuid; fixture_home uuid; fixture_other uuid; fixture_owner uuid;
begin
  select p.id into fixture_owner from classmate.profiles p join classmate.app_owners o on o.email=p.email
    where o.active and p.role='admin' and p.verification_status='active' limit 1;
  if fixture_owner is null then raise exception 'Owner fixture account is missing'; end if;
  perform set_config('request.jwt.claim.sub',fixture_owner::text,true);
  if (select count(*) from classmate.batches where is_active)<2 then
    select d.id into fixture_department from classmate.departments d join classmate.profiles p
      on d.email_prefix=substring(p.email from '^([a-z]+)[0-9]') where p.id=fixture_owner;
    if fixture_department is null then raise exception 'Owner academic department is missing'; end if;
    insert into classmate.batches(department_id,batch_number,academic_session)
      select d.id,substring(p.email from '^[a-z]+([0-9]{2})')::integer-d.session_offset,
        substring(p.email from '^[a-z]+([0-9]{2})')::integer
      from classmate.departments d cross join classmate.profiles p where d.id=fixture_department and p.id=fixture_owner
      on conflict(department_id,batch_number) do update set is_active=true returning id into fixture_home;
    insert into classmate.batches(department_id,batch_number,academic_session)
      select department_id,batch_number+1,academic_session+1 from classmate.batches where id=fixture_home
      on conflict(department_id,batch_number) do update set is_active=true returning id into fixture_other;
  end if;
  for owner_record in select p.* from classmate.profiles p join classmate.app_owners o on o.email=p.email
    where o.active and p.role='admin' and p.verification_status='active'
  loop
    perform set_config('request.jwt.claim.sub',owner_record.id::text,true);
    for batch_record in select b.*,d.email_prefix,d.session_offset from classmate.batches b
      join classmate.departments d on d.id=b.department_id where b.is_active
    loop
      expected := coalesce(owner_record.batch_id=batch_record.id or (
        owner_record.batch_id is null and owner_record.email ~ '^[a-z]+[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$'
        and batch_record.email_prefix=substring(owner_record.email from '^([a-z]+)[0-9]')
        and batch_record.academic_session=substring(owner_record.email from '^[a-z]+([0-9]{2})')::integer
        and batch_record.batch_number=batch_record.academic_session-batch_record.session_offset),false);
      select exists(select 1 from classmate.batch_friends(batch_record.id) where profile_id=owner_record.id) into actual;
      if actual is distinct from expected then raise exception 'Owner roster home-batch check failed'; end if;
      checked:=checked+1;
    end loop;
  end loop;
  if checked<2 then raise exception 'Need multiple batches to verify isolation'; end if;
  if has_function_privilege('anon','classmate.batch_friends(uuid,text,integer)','execute') then
    raise exception 'Anonymous directory access granted'; end if;
end $$;
rollback;
