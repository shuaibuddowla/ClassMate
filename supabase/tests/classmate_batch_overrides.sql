-- Test suite for Re-add / Dropout Student Override
-- Fully transactional fixture; rolls back at end.
begin;

create function pg_temp.assert_true(ok boolean, message text) returns void
language plpgsql as $$
begin
  if ok is distinct from true then raise exception '%', message; end if;
end $$;

-- 1. Setup fixture environment
select set_config('classmate.test_owner', (select id::text from classmate.profiles where role = 'admin' limit 1), true);

insert into classmate.departments (id, name, code, email_prefix, session_offset, is_active)
values ('f3100000-0000-4000-8000-000000000001', 'Override Test CSE', 'overridetestcse', 'otc', 3, true)
on conflict do nothing;

insert into classmate.batches (id, department_id, batch_number, academic_session) values
('f3100000-0000-4000-8000-000000000010', 'f3100000-0000-4000-8000-000000000001', 20, 23),
('f3100000-0000-4000-8000-000000000011', 'f3100000-0000-4000-8000-000000000001', 21, 24),
('f3100000-0000-4000-8000-000000000012', 'f3100000-0000-4000-8000-000000000001', 22, 25);

-- 2. Scenario A: Admin pre-registers an override BEFORE the student signs in
set local role authenticated;
select set_config('request.jwt.claim.sub', current_setting('classmate.test_owner'), true);

select classmate.set_batch_override('OTC23045', 'f3100000-0000-4000-8000-000000000011', 'Semester drop from Batch 20 to Batch 21');

select pg_temp.assert_true(
  exists(select 1 from classmate.batch_overrides where student_id = 'OTC23045' and effective_batch_id = 'f3100000-0000-4000-8000-000000000011'),
  'Pre-registered batch override row was not created in batch_overrides');

select pg_temp.assert_true(
  exists(select 1 from classmate.audit_log where action = 'set_batch_override' and details->>'student_id' = 'OTC23045'),
  'Audit log entry was not recorded for set_batch_override');

-- Now the student signs up with their original email 'otc23045@mbstu.ac.bd'
reset role;
insert into auth.users (id, email, email_confirmed_at, aud, role) values
('f3100000-0000-4000-8000-000000000020', 'otc23045@mbstu.ac.bd', now(), 'authenticated', 'authenticated');

insert into auth.identities (provider_id, user_id, identity_data, provider) values
('fixture-google-otc23045', 'f3100000-0000-4000-8000-000000000020',
 '{"sub":"fixture-google-otc23045","email":"otc23045@mbstu.ac.bd","email_verified":true}'::jsonb, 'google');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'f3100000-0000-4000-8000-000000000020', true);

-- Student completes onboarding
select classmate.complete_student_onboarding('f3100000-0000-4000-8000-000000000001');

select pg_temp.assert_true(
  (select batch_id = 'f3100000-0000-4000-8000-000000000011' and verification_status = 'active' and student_id = 'OTC23045'
   from classmate.profiles where id = 'f3100000-0000-4000-8000-000000000020'),
  'Student with override was not placed into effective_batch_id or active status');

select pg_temp.assert_true(
  (select effective_batch_id = 'f3100000-0000-4000-8000-000000000011'
   from classmate.profiles where id = 'f3100000-0000-4000-8000-000000000020'),
  'Profiles effective_batch_id column does not match effective_batch_id');

-- 3. Scenario B: Live override for an ALREADY existing student profile
reset role;
insert into auth.users (id, email, email_confirmed_at, aud, role) values
('f3100000-0000-4000-8000-000000000021', 'otc24099@mbstu.ac.bd', now(), 'authenticated', 'authenticated');

insert into auth.identities (provider_id, user_id, identity_data, provider) values
('fixture-google-otc24099', 'f3100000-0000-4000-8000-000000000021',
 '{"sub":"fixture-google-otc24099","email":"otc24099@mbstu.ac.bd","email_verified":true}'::jsonb, 'google');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'f3100000-0000-4000-8000-000000000021', true);

-- Onboards normally without override (session 24 - offset 3 = Batch 21)
select classmate.complete_student_onboarding('f3100000-0000-4000-8000-000000000001');

select pg_temp.assert_true(
  (select batch_id = 'f3100000-0000-4000-8000-000000000011' and verification_status = 'active'
   from classmate.profiles where id = 'f3100000-0000-4000-8000-000000000021'),
  'Standard student did not receive formula-derived batch 21');

-- Admin applies override to move student to Batch 22 live
select set_config('request.jwt.claim.sub', current_setting('classmate.test_owner'), true);

select classmate.set_batch_override('OTC24099', 'f3100000-0000-4000-8000-000000000012', 'Dropped to Batch 22');

select pg_temp.assert_true(
  (select batch_id = 'f3100000-0000-4000-8000-000000000012' and effective_batch_id = 'f3100000-0000-4000-8000-000000000012'
   from classmate.profiles where id = 'f3100000-0000-4000-8000-000000000021'),
  'Existing profile was not updated live to new effective_batch_id');

-- 4. Scenario C: Permission enforcement (Non-admin cannot set or delete overrides)
select set_config('request.jwt.claim.sub', 'f3100000-0000-4000-8000-000000000021', true);
do $$
begin
  begin
    perform classmate.set_batch_override('OTC24099', 'f3100000-0000-4000-8000-000000000010', 'Hacked');
    raise exception 'Unauthorized set_batch_override succeeded';
  exception when insufficient_privilege then null;
  end;
  begin
    perform classmate.delete_batch_override('OTC24099');
    raise exception 'Unauthorized delete_batch_override succeeded';
  exception when insufficient_privilege then null;
  end;
end $$;

-- 5. Scenario D: Admin deletes an override
select set_config('request.jwt.claim.sub', current_setting('classmate.test_owner'), true);
select classmate.delete_batch_override('OTC23045');

select pg_temp.assert_true(
  not exists(select 1 from classmate.batch_overrides where student_id = 'OTC23045'),
  'Override row was not deleted');

select pg_temp.assert_true(
  exists(select 1 from classmate.audit_log where action = 'delete_batch_override' and details->>'student_id' = 'OTC23045'),
  'Audit log entry was not recorded for delete_batch_override');

rollback;
