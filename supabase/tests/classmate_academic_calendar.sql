begin;
create function pg_temp.calendar_assert(ok boolean,message text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception '%',message; end if; end $$;
select set_config('classmate.calendar_owner',(select u.id::text from auth.users u join classmate.app_owners o on o.email=lower(u.email) where o.active and u.email_confirmed_at is not null limit 1),true);
select set_config('classmate.calendar_table','classmate.academic_calendar_events'::regclass::oid::text,true);
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('classmate.calendar_owner'),true);
select set_config('classmate.calendar_fixture',(classmate.save_calendar_event(null,'Calendar fixture','2026-10-06','2026-10-07','university')).id::text,true);
select pg_temp.calendar_assert((select end_date='2026-10-07' from classmate.academic_calendar_events where id=current_setting('classmate.calendar_fixture')::uuid),'Owner create');
select pg_temp.calendar_assert((classmate.save_calendar_event(current_setting('classmate.calendar_fixture')::uuid,'Edited fixture','2026-10-06','2026-10-06','classes')).scope='classes','Owner edit');
do $$ begin
  begin perform classmate.save_calendar_event(null,'Bad range','2026-10-07','2026-10-06','university'); raise exception 'Bad date range accepted'; exception when check_violation then null; end;
  begin perform classmate.save_calendar_event(null,'Bad scope','2026-10-07','2026-10-07','unknown'); raise exception 'Bad scope accepted'; exception when check_violation then null; end;
end $$;
select set_config('request.jwt.claim.sub','e3300000-0000-4000-8000-000000000012',true);
select pg_temp.calendar_assert((select count(*)=1 from classmate.academic_calendar_events where id=current_setting('classmate.calendar_fixture')::uuid),'Non-owner read across batches');
do $$ begin
  begin perform classmate.save_calendar_event(null,'Denied','2026-10-07','2026-10-07','university'); raise exception 'Non-owner write accepted'; exception when insufficient_privilege then null; end;
  begin perform classmate.delete_calendar_event(current_setting('classmate.calendar_fixture')::uuid); raise exception 'Non-owner delete accepted'; exception when insufficient_privilege then null; end;
  begin delete from classmate.academic_calendar_events where id=current_setting('classmate.calendar_fixture')::uuid; raise exception 'Direct delete accepted'; exception when insufficient_privilege then null; end;
end $$;
set local role anon;
select pg_temp.calendar_assert(not has_table_privilege('anon',current_setting('classmate.calendar_table')::oid,'SELECT'),'Anonymous calendar denied');
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('classmate.calendar_owner'),true);
select classmate.delete_calendar_event(current_setting('classmate.calendar_fixture')::uuid);
select pg_temp.calendar_assert((select count(*)=0 from classmate.academic_calendar_events where id=current_setting('classmate.calendar_fixture')::uuid),'Owner delete');
rollback;
