-- Disposable staging only. Every fixture rolls back.
begin;

create function pg_temp.assert_true(ok boolean, message text) returns void
language plpgsql as $$
begin
  if ok is distinct from true then raise exception '%', message; end if;
end $$;

select pg_temp.assert_true(
  classmate.before_user_created('{"user":{"email":"fixture@mbstu.ac.bd","app_metadata":{"provider":"google"}}}'::jsonb) = '{}'::jsonb,
  'University Google signup was rejected by hook');

insert into auth.users (id, email, email_confirmed_at, aud, role)
select 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'::uuid, email, now(), 'authenticated', 'authenticated'
from classmate.app_owners where active;
insert into auth.users (id, email, email_confirmed_at, aud, role) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2', 'fixture22@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3', 'fixture23@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4', 'fixturepending@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5', 'fixturecr@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6', 'fixtureteacher@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa7', 'fixtureexpiredcr@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8', 'ce25046@mbstu.ac.bd', now(), 'authenticated', 'authenticated');
insert into auth.identities (provider_id, user_id, identity_data, provider) values
  ('fixture-google-ce25046', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8',
   '{"sub":"fixture-google-ce25046","email":"ce25046@mbstu.ac.bd","email_verified":true}'::jsonb, 'google');

insert into classmate.departments (id, name, code, is_active) values
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', 'Fixture Department', 'fixture', true);
insert into classmate.batches (id, department_id, batch_number, academic_session) values
  ('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', (select id from classmate.departments where code = 'cse'), 22, 25),
  ('cccccccc-cccc-4ccc-8ccc-ccccccccccc2', (select id from classmate.departments where code = 'cse'), 23, 26),
  ('cccccccc-cccc-4ccc-8ccc-ccccccccccc3', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', 1, 1);

insert into classmate.profiles
  (id, email, role, department_id, batch_id, verification_status, is_cr, cr_batch_id, cr_valid_until)
values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', (select email from classmate.app_owners where active), 'admin', null, null, 'active', false, null, null),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2', 'fixture22@mbstu.ac.bd', 'student', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'active', false, null, null),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3', 'fixture23@mbstu.ac.bd', 'student', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc2', 'active', false, null, null),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4', 'fixturepending@mbstu.ac.bd', 'student', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', null, 'pending', false, null, null),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5', 'fixturecr@mbstu.ac.bd', 'student', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'active', true, 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', now() + interval '1 day'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6', 'fixtureteacher@mbstu.ac.bd', 'teacher', (select id from classmate.departments where code = 'cse'), null, 'active', false, null, null),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa7', 'fixtureexpiredcr@mbstu.ac.bd', 'student', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'active', true, 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', now() - interval '1 day');

update classmate.semesters set status = 'active'
where semester_number = 1 and batch_id in
  ('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'cccccccc-cccc-4ccc-8ccc-ccccccccccc2');
select pg_temp.assert_true(
  (select count(*) from classmate.semesters where batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1') = 8,
  'A new batch did not receive eight semester slots');
do $$
begin
  begin
    update classmate.semesters set status = 'active'
    where batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1' and semester_number = 2;
    raise exception 'Second active semester was accepted';
  exception when unique_violation then null;
  end;
end $$;
insert into classmate.courses (id, department_id, course_code, course_title, course_type) values
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1', (select id from classmate.departments where code = 'cse'), 'FIX101', 'Fixture Course A', 'theory'),
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee2', (select id from classmate.departments where code = 'cse'), 'FIX102', 'Fixture Course B', 'theory');
insert into classmate.semester_courses (id, semester_id, course_id) values
  ('ffffffff-ffff-4fff-8fff-fffffffffff1', (select id from classmate.semesters where batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1' and semester_number = 1), 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1'),
  ('ffffffff-ffff-4fff-8fff-fffffffffff2', (select id from classmate.semesters where batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc2' and semester_number = 1), 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee2');
insert into classmate.teacher_allowlist (email, department_id)
values ('fixtureteacher@mbstu.ac.bd', (select id from classmate.departments where code = 'cse'));
insert into classmate.teacher_course_assignments (teacher_id, semester_course_id, assigned_by)
values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6', 'ffffffff-ffff-4fff-8fff-fffffffffff1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1');

insert into classmate.notices (id, department_id, batch_id, author_id, title) values
  ('dddddddd-dddd-4ddd-8ddd-ddddddddddd1', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'Batch 22 fixture'),
  ('dddddddd-dddd-4ddd-8ddd-ddddddddddd2', (select id from classmate.departments where code = 'cse'), 'cccccccc-cccc-4ccc-8ccc-ccccccccccc2', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'Batch 23 fixture'),
  ('dddddddd-dddd-4ddd-8ddd-ddddddddddd3', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', 'cccccccc-cccc-4ccc-8ccc-ccccccccccc3', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'Other department fixture');

select pg_temp.assert_true(
  (select count(*) from classmate.notification_outbox where kind = 'notice') = 3
  and exists (select 1 from classmate.notification_outbox
    where record_id = 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1'
      and batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1'
      and routing_label = 'dept_cse_batch_22')
  and exists (select 1 from classmate.notification_outbox
    where record_id = 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2'
      and batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc2'
      and routing_label = 'dept_cse_batch_23')
  and exists (select 1 from classmate.notification_outbox
    where record_id = 'dddddddd-dddd-4ddd-8ddd-ddddddddddd3'
      and batch_id = 'cccccccc-cccc-4ccc-8ccc-ccccccccccc3'
      and routing_label = 'dept_fixture_batch_1'),
  'Notice events did not retain their server-derived department and batch');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2', true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices) = 1
  and (select count(*) from classmate.notices where id = 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1') = 1,
  'Batch 22 saw foreign notices or lost own notice');
select pg_temp.assert_true(not classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', null),
  'Normal student can post');
select pg_temp.assert_true(not has_table_privilege('authenticated', 'classmate.profiles', 'UPDATE'),
  'Authenticated role can directly update profiles');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3', true);
select pg_temp.assert_true(
  (select count(*) from classmate.notices) = 1
  and (select count(*) from classmate.notices where id = 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2') = 1,
  'Batch 23 saw foreign notices or lost own notice');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4', true);
select pg_temp.assert_true(
  (select count(*) from classmate.profiles) = 1
  and (select count(*) from classmate.notices) = 0
  and (select count(*) from classmate.batches) = 0,
  'Pending student read academic data or another profile');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5', true);
select pg_temp.assert_true(
  classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', null)
  and not classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc2', null),
  'CR posting scope failed');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa7', true);
select pg_temp.assert_true(not classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', null),
  'Expired CR can still post');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6', true);
select pg_temp.assert_true(
  classmate.can_manage_course('ffffffff-ffff-4fff-8fff-fffffffffff1')
  and not classmate.can_manage_course('ffffffff-ffff-4fff-8fff-fffffffffff2')
  and classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'ffffffff-ffff-4fff-8fff-fffffffffff1')
  and not classmate.can_post('cccccccc-cccc-4ccc-8ccc-ccccccccccc2', 'ffffffff-ffff-4fff-8fff-fffffffffff2')
  and (select count(*) from classmate.notices) = 1,
  'Teacher assignment or batch scope failed');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8', true);
select pg_temp.assert_true(
  (classmate.create_or_initialize_profile()).role = 'student',
  'Institutional Google identity did not initialize a student profile');
select classmate.complete_student_onboarding((select id from classmate.departments where code = 'cse'));
select pg_temp.assert_true(exists (
  select 1 from classmate.profiles p join classmate.batches b on b.id = p.batch_id
  where p.id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8'
    and p.student_id = 'CE25046' and p.academic_session = 25
    and p.verification_status = 'active' and b.batch_number = 22),
  'CSE institutional email did not resolve to session 25 and batch 22');

select set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', true);
select pg_temp.assert_true(classmate.is_owner() and (select count(*) from classmate.notices) = 3,
  'Seeded owner cannot see all notices');

reset role;
rollback;
