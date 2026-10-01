begin;

create function classmate.can_manage_routine(target_course uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or classmate.can_manage_course(target_course) or exists (
    select 1 from classmate.profiles p
    join classmate.semester_courses sc on sc.id = target_course
    join classmate.semesters s on s.id = sc.semester_id
    where p.id = (select auth.uid()) and p.role = 'student'
      and p.verification_status = 'active' and p.is_cr
      and p.cr_batch_id = s.batch_id
      and (p.cr_valid_until is null or p.cr_valid_until > now())
  );
$$;

create function classmate.save_routine_slot(target_id uuid, target_semester_course uuid,
  target_day smallint, target_start time, target_end time, target_room text)
returns classmate.routine_slots language plpgsql security definer set search_path = '' as $$
declare slot classmate.routine_slots%rowtype;
declare course_type text;
begin
  if not classmate.can_manage_routine(target_semester_course) then
    raise exception 'Routine permission denied' using errcode = '42501';
  end if;
  select c.course_type into course_type
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

create function classmate.delete_routine_slot(target_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare slot classmate.routine_slots%rowtype;
begin
  select * into slot from classmate.routine_slots where id = target_id;
  if slot.id is null or not classmate.can_manage_routine(slot.semester_course_id) then
    raise exception 'Routine permission denied' using errcode = '42501';
  end if;
  delete from classmate.routine_slots where id = target_id;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'delete_routine_slot', target_id);
end;
$$;

create function classmate.edit_notice(target_id uuid, target_title text, target_body text)
returns classmate.notices language plpgsql security definer set search_path = '' as $$
declare notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (classmate.is_owner() or
    (notice_row.author_id = auth.uid() and
      classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  update classmate.notices set title = trim(target_title), body = coalesce(target_body, '')
    where id = target_id returning * into notice_row;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'edit_notice', target_id);
  return notice_row;
end;
$$;

create function classmate.delete_notice(target_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (classmate.is_owner() or
    (notice_row.author_id = auth.uid() and
      classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  delete from classmate.notices where id = target_id;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'delete_notice', target_id);
end;
$$;

revoke all on function classmate.can_manage_routine(uuid) from public, anon;
revoke all on function classmate.save_routine_slot(uuid,uuid,smallint,time,time,text) from public, anon;
revoke all on function classmate.delete_routine_slot(uuid) from public, anon;
revoke all on function classmate.edit_notice(uuid,text,text) from public, anon;
revoke all on function classmate.delete_notice(uuid) from public, anon;
grant execute on function classmate.can_manage_routine(uuid) to authenticated;
grant execute on function classmate.save_routine_slot(uuid,uuid,smallint,time,time,text) to authenticated;
grant execute on function classmate.delete_routine_slot(uuid) to authenticated;
grant execute on function classmate.edit_notice(uuid,text,text) to authenticated;
grant execute on function classmate.delete_notice(uuid) to authenticated;

commit;
