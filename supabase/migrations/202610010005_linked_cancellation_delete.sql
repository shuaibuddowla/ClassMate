begin;

create or replace function classmate.delete_notice(target_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare notice_row classmate.notices%rowtype;
begin
  select * into notice_row from classmate.notices where id = target_id;
  if notice_row.id is null or not (classmate.is_owner() or
    (notice_row.author_id = auth.uid() and
      classmate.can_post(notice_row.batch_id, notice_row.semester_course_id))) then
    raise exception 'Notice permission denied' using errcode = '42501';
  end if;
  delete from classmate.notification_outbox
    where kind = 'notice' and record_id = target_id;
  delete from classmate.notices where id = target_id;
  if notice_row.class_change_id is not null then
    delete from classmate.notification_outbox
      where kind = 'class_change' and record_id = notice_row.class_change_id;
    delete from classmate.class_changes where id = notice_row.class_change_id;
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'delete_notice', target_id);
end;
$$;

commit;
