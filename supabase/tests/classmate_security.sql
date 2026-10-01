-- Run with psql as a database owner against a disposable Supabase project.
-- Supply UUID variables for existing Google test accounts and seeded records:
-- -v student22=... -v student23=... -v pending=... -v cr22=...
-- -v teacher=... -v owner=... -v batch22=... -v batch23=...
-- -v foreign_batch=... -v notice22=... -v notice23=...
-- -v foreign_notice=... -v course_a=... -v course_b=...
-- -v expired_cr=... -v semester2=... -v semester3=...
-- The fixtures must include published notices in all three batches.
begin;
create function pg_temp.assert_true(ok boolean, message text) returns void
language plpgsql as $$ begin
  if ok is distinct from true then raise exception '%', message; end if;
end $$;
set local role authenticated;

select set_config('request.jwt.claim.sub', :'student22', true);
select pg_temp.assert_true(exists (select 1 from classmate.notices
  where id = :'notice22'::uuid), 'Batch 22 student cannot see own notice');
select pg_temp.assert_true(not exists (select 1 from classmate.notices where id in
  (:'notice23'::uuid, :'foreign_notice'::uuid)), 'Cross-batch or department notice leaked');
select pg_temp.assert_true(not classmate.can_post(:'batch22'::uuid, null),
  'Normal student can post');
select pg_temp.assert_true(not has_table_privilege('authenticated', 'classmate.profiles', 'UPDATE'),
  'Student has direct profile update grant');

select set_config('request.jwt.claim.sub', :'pending', true);
select pg_temp.assert_true(exists (select 1 from classmate.profiles where id = auth.uid()),
  'Pending user cannot read own verification state');
select pg_temp.assert_true(not exists (select 1 from classmate.notices)
  and not exists (select 1 from classmate.file_metadata)
  and not exists (select 1 from classmate.batches), 'Pending user accessed academic data');

select set_config('request.jwt.claim.sub', :'cr22', true);
select pg_temp.assert_true(classmate.can_post(:'batch22'::uuid, null)
  and not classmate.can_post(:'batch23'::uuid, null)
  and not classmate.can_post(:'foreign_batch'::uuid, null), 'CR batch permission failed');
select set_config('request.jwt.claim.sub', :'expired_cr', true);
select pg_temp.assert_true(not classmate.can_post(:'batch22'::uuid, null),
  'Expired CR can still post');

select set_config('request.jwt.claim.sub', :'teacher', true);
select pg_temp.assert_true(classmate.can_manage_course(:'course_a'::uuid)
  and not classmate.can_manage_course(:'course_b'::uuid), 'Teacher course isolation failed');

select set_config('request.jwt.claim.sub', :'owner', true);
select pg_temp.assert_true(classmate.is_owner(), 'Owner bootstrap failed');
select classmate.publish_semester(:'semester3'::uuid);
select pg_temp.assert_true(
  (select status = 'completed' from classmate.semesters where id = :'semester2'::uuid)
  and (select status = 'active' from classmate.semesters where id = :'semester3'::uuid)
  and (select count(*) = 1 from classmate.semesters where batch_id = :'batch22'::uuid
    and status = 'active'), 'Semester publish was not atomic');
reset role;
select pg_temp.assert_true(classmate.routing_label(:'batch22'::uuid)
  <> classmate.routing_label(:'batch23'::uuid)
  and classmate.routing_label(:'batch22'::uuid)
  <> classmate.routing_label(:'foreign_batch'::uuid), 'Notification labels collided');
rollback;
