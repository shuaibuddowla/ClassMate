begin;
alter table classmate.bus_schedules
  add column schedule_kind text not null default 'legacy' check(schedule_kind in ('legacy','office_open','closed')),
  add column city_departure_time time,
  add constraint paired_student_bus_times check(schedule_kind='legacy' or city_departure_time is not null);
create unique index student_bus_pair_unique on classmate.bus_schedules(schedule_kind,departure_time,city_departure_time)
  where active and schedule_kind<>'legacy';

create function classmate.save_student_bus_schedule(target_id uuid,target_kind text,
  target_campus_departure time,target_city_departure time,target_active boolean default true)
returns classmate.bus_schedules language plpgsql security definer set search_path='' as $$
declare saved classmate.bus_schedules%rowtype;
begin
  if not classmate.can_edit_bus_schedules() then raise exception 'Owner or active CR required' using errcode='42501'; end if;
  if target_kind is null or target_kind not in ('office_open','closed') then raise exception 'Choose office open or closed days'; end if;
  if target_campus_departure is null or target_city_departure is null or target_campus_departure>='24:00'::time or target_city_departure>='24:00'::time then
    raise exception 'Choose both departure times'; end if;
  if target_id is null then
    insert into classmate.bus_schedules(route_name,departure_time,city_departure_time,schedule_kind,
      origin,destination,weekdays,active,created_by)
    values('Student bus schedule',target_campus_departure,target_city_departure,target_kind,'Campus','City',
      case when target_kind='office_open' then array[0,1,2,3,4]::smallint[] else array[5,6]::smallint[] end,target_active,auth.uid())
    returning * into saved;
  else
    update classmate.bus_schedules set route_name='Student bus schedule',departure_time=target_campus_departure,
      city_departure_time=target_city_departure,schedule_kind=target_kind,origin='Campus',destination='City',notes=null,
      weekdays=case when target_kind='office_open' then array[0,1,2,3,4]::smallint[] else array[5,6]::smallint[] end,
      active=target_active,updated_at=now() where id=target_id returning * into saved;
    if not found then raise exception 'Bus schedule no longer exists'; end if;
  end if;
  insert into classmate.audit_log(actor_id,action,target_id) values(auth.uid(),'save_student_bus_schedule',saved.id);
  return saved;
exception when unique_violation then raise exception 'These departure times are already configured for this day type';
end $$;
revoke all on function classmate.save_student_bus_schedule(uuid,text,time,time,boolean) from public,anon;
grant execute on function classmate.save_student_bus_schedule(uuid,text,time,time,boolean) to authenticated;
notify pgrst,'reload schema';
commit;
