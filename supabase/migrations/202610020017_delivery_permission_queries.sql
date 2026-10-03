begin;
-- Cache per-device permission plans; avoid enumerating a release audience per receipt.
create or replace function classmate.notification_device_allowed(target_event uuid,target_profile uuid,target_hash text) returns boolean
language plpgsql stable security definer set search_path='' as $$
begin
  return exists(select 1 from classmate.notification_outbox e
    join classmate.profiles p on p.id=target_profile and p.verification_status='active'
    join classmate.device_tokens dt on dt.profile_id=p.id and dt.token_hash=target_hash
    left join classmate.batches b on b.id=e.batch_id and b.department_id=e.department_id and b.is_active
    where e.id=target_event and (
      (e.kind='app_update' and (
        (p.role='student' and exists(select 1 from classmate.batches own_batch where own_batch.id=p.batch_id and own_batch.is_active))
        or (p.role='teacher' and exists(select 1 from classmate.teacher_allowlist t where t.email=p.email and t.active))
        or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.email=p.email and o.active))))
      or (e.kind<>'app_update' and b.id is not null and (
        (p.role='student' and p.batch_id=b.id and p.department_id=b.department_id)
        or (p.role='teacher' and exists(select 1 from classmate.teacher_course_assignments a
          join classmate.semester_courses sc on sc.id=a.semester_course_id
          join classmate.semesters s on s.id=sc.semester_id
          join classmate.teacher_allowlist t on t.email=p.email and t.department_id=b.department_id and t.active
          where a.teacher_id=p.id and a.active and s.batch_id=b.id and s.status='active'
            and (e.semester_course_id is null or sc.id=e.semester_course_id)))))));
end;
$$;

create or replace function classmate.report_notification_receipt(target_event uuid,target_hash text,target_state text,target_version integer) returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.notification_device_allowed(target_event,auth.uid(),target_hash) then raise exception 'Recipient required' using errcode='42501'; end if;
  insert into classmate.notification_client_receipts(outbox_id,profile_id,token_hash,state,app_version)
    values(target_event,auth.uid(),target_hash,target_state,target_version)
    on conflict(outbox_id,profile_id,token_hash) do update set state=excluded.state,app_version=excluded.app_version,received_at=now();
end $$;
notify pgrst,'reload schema';
commit;
