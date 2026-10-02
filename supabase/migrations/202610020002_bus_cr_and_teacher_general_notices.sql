begin;
create function classmate.can_edit_bus_schedules() returns boolean
language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p join classmate.batches b on b.id = p.cr_batch_id
    where p.id = (select auth.uid()) and p.role = 'student' and p.verification_status = 'active'
      and p.is_cr and p.batch_id = p.cr_batch_id and b.is_active
      and (p.cr_valid_until is null or p.cr_valid_until > now())
  );
$$;
revoke all on function classmate.can_edit_bus_schedules() from public, anon;
grant execute on function classmate.can_edit_bus_schedules() to authenticated;
create or replace function classmate.save_bus_schedule(target_id uuid, target_route text,
  target_departure time, target_origin text, target_destination text,
  target_weekdays smallint[], target_notes text, target_active boolean)
returns classmate.bus_schedules language plpgsql security definer set search_path = '' as $$
declare bus classmate.bus_schedules%rowtype;
begin
  if not classmate.can_edit_bus_schedules() then
    raise exception 'Owner or active CR required' using errcode = '42501';
  end if;
  if target_id is null then
    insert into classmate.bus_schedules(route_name, departure_time, origin, destination,
      weekdays, notes, active, created_by)
    values (trim(target_route), target_departure, trim(target_origin),
      trim(target_destination), target_weekdays, target_notes, target_active, auth.uid())
    returning * into bus;
  else
    update classmate.bus_schedules set route_name = trim(target_route),
      departure_time = target_departure, origin = trim(target_origin),
      destination = trim(target_destination), weekdays = target_weekdays,
      notes = target_notes, active = target_active, updated_at = now()
    where id = target_id returning * into bus;
    if bus.id is null then raise exception 'Bus schedule not found'; end if;
  end if;
  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'save_bus_schedule', bus.id);
  return bus;
end;
$$;

create or replace function classmate.can_post(target_batch uuid, target_course uuid default null)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id = (select auth.uid())
      and p.verification_status = 'active' and (
        (target_course is null and p.role = 'student' and p.is_cr
          and p.cr_batch_id = target_batch
          and (p.cr_valid_until is null or p.cr_valid_until > now()))
        or (target_course is null and p.role = 'teacher' and exists (
          select 1 from classmate.semester_courses sc join classmate.semesters s on s.id = sc.semester_id
          where s.batch_id = target_batch and s.status = 'active' and classmate.can_manage_course(sc.id)))
        or (target_course is not null and classmate.can_manage_course(target_course)
          and exists (select 1 from classmate.semester_courses sc
            join classmate.semesters s on s.id = sc.semester_id
            where sc.id = target_course and s.batch_id = target_batch
              and s.status = 'active'))
      )
  );
$$;

notify pgrst, 'reload schema';
commit;
