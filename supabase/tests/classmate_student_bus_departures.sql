begin;
create function pg_temp.assert_true(ok boolean,message text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception '%',message; end if; end $$;
select set_config('classmate.bus_test_owner',(select id::text from classmate.profiles where role='admin' limit 1),true);
insert into classmate.departments(id,name,code,is_active) values('e3200000-0000-4000-8000-000000000001','Bus fixture','busfixture',true);
insert into classmate.batches(id,department_id,batch_number,academic_session) values
('e3200000-0000-4000-8000-000000000002','e3200000-0000-4000-8000-000000000001',1,1);
insert into auth.users(id,email,email_confirmed_at,aud,role) values
('e3200000-0000-4000-8000-000000000011','busfixturecr@mbstu.ac.bd',now(),'authenticated','authenticated'),
('e3200000-0000-4000-8000-000000000012','busfixturestudent@mbstu.ac.bd',now(),'authenticated','authenticated');
insert into classmate.profiles(id,email,role,department_id,batch_id,verification_status,is_cr,cr_batch_id) values
('e3200000-0000-4000-8000-000000000011','busfixturecr@mbstu.ac.bd','student','e3200000-0000-4000-8000-000000000001','e3200000-0000-4000-8000-000000000002','active',true,'e3200000-0000-4000-8000-000000000002'),
('e3200000-0000-4000-8000-000000000012','busfixturestudent@mbstu.ac.bd','student','e3200000-0000-4000-8000-000000000001','e3200000-0000-4000-8000-000000000002','active',false,null);
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('classmate.bus_test_owner'),true);
-- Unusual times avoid collision with a pre-existing staging schedule.
select set_config('classmate.bus_test_id',(classmate.save_student_bus_schedule(null,'office_open','00:01','00:31')).id::text,true);
select pg_temp.assert_true((select schedule_kind='office_open' and departure_time='00:01'::time and city_departure_time='00:31'::time
  and weekdays=array[0,1,2,3,6]::smallint[] and notes is null from classmate.bus_schedules where id=current_setting('classmate.bus_test_id')::uuid),'Paired office times');
select set_config('classmate.bus_test_closed',(classmate.save_student_bus_schedule(null,'closed','00:02','00:32')).id::text,true);
select pg_temp.assert_true((select weekdays=array[4,5]::smallint[] and schedule_kind='closed'
  from classmate.bus_schedules where id=current_setting('classmate.bus_test_closed')::uuid),'Closed days');
do $$ begin
  begin perform classmate.save_student_bus_schedule(null,'office_open','00:01','00:31'); raise sqlstate 'XX000' using message='Duplicate accepted';
  exception when raise_exception then perform pg_temp.assert_true(sqlerrm like '%already configured%','Duplicate error'); end;
  begin perform classmate.save_student_bus_schedule(null,'office_open','00:01',null); raise sqlstate 'XX000' using message='Missing city time accepted';
  exception when raise_exception then perform pg_temp.assert_true(sqlerrm like '%both departure%','Missing time error'); end;
  begin perform classmate.save_student_bus_schedule(null,'invalid','00:01','00:31'); raise sqlstate 'XX000' using message='Invalid kind accepted';
  exception when raise_exception then perform pg_temp.assert_true(sqlerrm like '%Choose office%','Kind validation'); end;
end $$;
select set_config('request.jwt.claim.sub','e3200000-0000-4000-8000-000000000011',true);
select classmate.save_student_bus_schedule(current_setting('classmate.bus_test_id')::uuid,'office_open','13:10','13:40');
select pg_temp.assert_true((select departure_time='13:10'::time and city_departure_time='13:40'::time
  from classmate.bus_schedules where id=current_setting('classmate.bus_test_id')::uuid),'CR editing and afternoon times');
select classmate.save_student_bus_schedule(current_setting('classmate.bus_test_closed')::uuid,'closed','20:20','21:00',false);
reset role;
select pg_temp.assert_true((select not active and city_departure_time='21:00'::time
  from classmate.bus_schedules where id=current_setting('classmate.bus_test_closed')::uuid),'Disable and evening times');
set local role authenticated;
select set_config('request.jwt.claim.sub','e3200000-0000-4000-8000-000000000012',true);
do $$ begin
  begin perform classmate.save_student_bus_schedule(null,'office_open','12:00','12:30'); raise exception 'Student write accepted'; exception when insufficient_privilege then null; end;
end $$;
reset role;
update classmate.profiles set cr_valid_until=now()-interval '1 minute' where id='e3200000-0000-4000-8000-000000000011';
set local role authenticated;
select set_config('request.jwt.claim.sub','e3200000-0000-4000-8000-000000000011',true);
do $$ begin
  begin perform classmate.save_student_bus_schedule(null,'office_open','12:00','12:30'); raise exception 'Expired CR write accepted'; exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('classmate.bus_test_owner'),true);
select pg_temp.assert_true((classmate.save_bus_schedule(null,'Legacy fixture','23:57','Campus','City',array[0,1,2,3,4]::smallint[],'Original notes',true)).schedule_kind='legacy','Old APK RPC preserved');
rollback;
