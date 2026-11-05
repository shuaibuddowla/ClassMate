begin;

create function classmate.batch_active_ban_list(target_batch uuid)
returns table(profile_id uuid, ends_at timestamptz)
language plpgsql stable security definer set search_path = '' as $$
declare caller classmate.profiles%rowtype;
begin
  select * into caller from classmate.profiles
    where id=auth.uid() and verification_status='active';
  if caller.id is null or not classmate.is_active() then
    raise exception 'Active account required' using errcode='42501';
  end if;
  if not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'Active batch required' using errcode='22023';
  end if;
  if not (classmate.is_owner() or (caller.role='student' and caller.is_cr
      and caller.batch_id=target_batch and caller.cr_batch_id=target_batch
      and (caller.cr_valid_until is null or caller.cr_valid_until>now()))) then
    raise exception 'Batch CR or administrator permission required' using errcode='42501';
  end if;
  return query
    select distinct b.profile_id,b.ends_at
    from classmate.student_bans b
    where b.batch_id=target_batch and b.lifted_at is null
      and b.starts_at<=now() and b.ends_at>now()
    order by b.ends_at desc;
end;
$$;

create function classmate.owner_delete_teacher(target_record uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare teacher classmate.teacher_directory%rowtype; linked_courses integer;
begin
  perform classmate.require_owner();
  select * into teacher from classmate.teacher_directory where id=target_record for update;
  if not found then raise exception 'Teacher record not found'; end if;

  select count(*) into linked_courses from classmate.semester_courses
    where teacher_record_id=teacher.id;
  if teacher.email is not null then
    update classmate.teacher_allowlist set active=false
      where email=teacher.email and department_id=teacher.department_id and active;
  end if;
  if teacher.profile_id is not null then
    update classmate.teacher_course_assignments set active=false
      where teacher_id=teacher.profile_id and semester_course_id in
        (select id from classmate.semester_courses where teacher_record_id=teacher.id);
  end if;
  delete from classmate.teacher_directory where id=teacher.id;
  insert into classmate.audit_log(actor_id,action,target_id,details)
    values(auth.uid(),'delete_teacher_directory',teacher.id,
      jsonb_build_object('name',teacher.full_name,'unassigned_courses',linked_courses));
  return jsonb_build_object('deleted',true,'unassigned_courses',linked_courses);
end;
$$;

revoke all on function classmate.batch_active_ban_list(uuid),
  classmate.owner_delete_teacher(uuid) from public,anon,authenticated;
grant execute on function classmate.batch_active_ban_list(uuid),
  classmate.owner_delete_teacher(uuid) to authenticated;
notify pgrst,'reload schema';
commit;
