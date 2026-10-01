begin;

alter table classmate.notices
  add column class_change_id uuid unique references classmate.class_changes(id) on delete set null;

create function classmate.post_cancellation_notice(
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
  select b.department_id, c.course_title into target_department, course_title
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
  insert into classmate.notices(department_id, batch_id, semester_course_id,
    author_id, title, body, class_change_id)
  values (target_department, target_batch, target_course, auth.uid(),
    left('Class cancelled · ' || course_title, 200),
    'Class on ' || to_char(change_date, 'DD Mon YYYY') || ' is cancelled.', change_row.id)
  returning * into existing_notice;
  return existing_notice;
end;
$$;
revoke all on function classmate.post_cancellation_notice(uuid,uuid,date) from public, anon;
grant execute on function classmate.post_cancellation_notice(uuid,uuid,date) to authenticated;

create function classmate.timetable_details(
  target_batch uuid, target_date date, target_course_ids uuid[])
returns table(semester_course_id uuid, teacher_name text, cancelled boolean)
language sql stable security definer set search_path = '' as $$
  select sc.id,
    (select nullif(trim(p.full_name), '')
      from classmate.teacher_course_assignments a
      join classmate.profiles p on p.id = a.teacher_id
      where a.semester_course_id = sc.id and a.active
        and p.role = 'teacher' and p.verification_status = 'active'
      order by a.created_at limit 1),
    exists (select 1 from classmate.class_changes cc
      where cc.batch_id = target_batch and cc.semester_course_id = sc.id
        and cc.effective_date = target_date and cc.kind = 'cancelled')
  from classmate.semester_courses sc
  join classmate.semesters s on s.id = sc.semester_id
  where sc.id = any(target_course_ids) and s.batch_id = target_batch
    and s.status = 'active' and classmate.can_read_batch(target_batch);
$$;
revoke all on function classmate.timetable_details(uuid,date,uuid[]) from public, anon;
grant execute on function classmate.timetable_details(uuid,date,uuid[]) to authenticated;

create function classmate.notice_author_details(target_ids uuid[])
returns table(notice_id uuid, author_name text, avatar_url text)
language sql stable security definer set search_path = '' as $$
  select n.id, coalesce(nullif(trim(p.full_name), ''), 'ClassMate member'),
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',
      u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture')
      else null end
  from classmate.notices n
  join classmate.profiles p on p.id = n.author_id
  join auth.users u on u.id = n.author_id
  where n.id = any(target_ids) and classmate.can_read_notice(n.id);
$$;
revoke all on function classmate.notice_author_details(uuid[]) from public, anon;
grant execute on function classmate.notice_author_details(uuid[]) to authenticated;

commit;
