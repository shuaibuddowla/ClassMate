-- Verification test for targeted deposit and attendance notifications fix
begin;

create function pg_temp.assert_eq(actual anyelement, expected anyelement, message text) returns void
language plpgsql as $$ begin
  if actual is distinct from expected then
    raise exception 'Assertion failed: % (expected %, got %)', message, expected, actual;
  end if;
end $$;

-- 1. Setup isolated test department and batch
insert into classmate.departments(id, name, code, is_active)
values ('e7000000-0000-4000-8000-000000000001', 'Targeted Test Dept', 'tgdept', true);

insert into classmate.batches(id, department_id, batch_number, academic_session)
values ('e7000000-0000-4000-8000-000000000002', 'e7000000-0000-4000-8000-000000000001', 99, 26);

-- 2. Setup 3 students in this batch: CR, Student A, Student B (all lowercase emails)
insert into auth.users(id, email, email_confirmed_at, aud, role) values
  ('e7000000-0000-4000-8000-000000000011', 'cr99@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('e7000000-0000-4000-8000-000000000012', 'studenta99@mbstu.ac.bd', now(), 'authenticated', 'authenticated'),
  ('e7000000-0000-4000-8000-000000000013', 'studentb99@mbstu.ac.bd', now(), 'authenticated', 'authenticated');

insert into classmate.profiles(id, email, full_name, student_id, role, department_id, batch_id, verification_status, is_cr, cr_batch_id) values
  ('e7000000-0000-4000-8000-000000000011', 'cr99@mbstu.ac.bd', 'Test CR', 'CE99001', 'student', 'e7000000-0000-4000-8000-000000000001', 'e7000000-0000-4000-8000-000000000002', 'active', true, 'e7000000-0000-4000-8000-000000000002'),
  ('e7000000-0000-4000-8000-000000000012', 'studenta99@mbstu.ac.bd', 'Student A', 'CE99002', 'student', 'e7000000-0000-4000-8000-000000000001', 'e7000000-0000-4000-8000-000000000002', 'active', false, null),
  ('e7000000-0000-4000-8000-000000000013', 'studentb99@mbstu.ac.bd', 'Student B', 'CE99003', 'student', 'e7000000-0000-4000-8000-000000000001', 'e7000000-0000-4000-8000-000000000002', 'active', false, null);

-- 3. Register device tokens for Student A and Student B
insert into classmate.device_tokens(profile_id, fcm_token) values
  ('e7000000-0000-4000-8000-000000000012', 'fcm-token-student-a-99'),
  ('e7000000-0000-4000-8000-000000000013', 'fcm-token-student-b-99');

-- 4. Test DEPOSIT: Create a deposit transaction for Student A
insert into classmate.batch_fund_transactions(
  id, batch_id, type, amount, title, student_name, student_id, student_profile_id, created_by
) values (
  'e7000000-0000-4000-8000-000000000020',
  'e7000000-0000-4000-8000-000000000002',
  'inflow', 500, 'Picnic Fee', 'Student A', 'CE99002',
  'e7000000-0000-4000-8000-000000000012',
  'e7000000-0000-4000-8000-000000000011'
);

-- Case 4a: Outbox row with explicit recipient_profile_id
insert into classmate.notification_outbox(
  id, kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
) values (
  'e7000000-0000-4000-8000-000000000030',
  'batch_fund_deposit',
  'e7000000-0000-4000-8000-000000000020',
  'e7000000-0000-4000-8000-000000000002',
  'e7000000-0000-4000-8000-000000000001',
  'dept_tgdept_batch_99',
  'e7000000-0000-4000-8000-000000000012'
);

-- Case 4b: Legacy outbox row where recipient_profile_id was NULL
insert into classmate.batch_fund_transactions(
  id, batch_id, type, amount, title, student_name, student_id, student_profile_id, created_by
) values (
  'e7000000-0000-4000-8000-000000000021',
  'e7000000-0000-4000-8000-000000000002',
  'inflow', 300, 'T-Shirt Fee', 'Student A', 'CE99002',
  'e7000000-0000-4000-8000-000000000012',
  'e7000000-0000-4000-8000-000000000011'
);

insert into classmate.notification_outbox(
  id, kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
) values (
  'e7000000-0000-4000-8000-000000000031',
  'batch_fund_deposit',
  'e7000000-0000-4000-8000-000000000021',
  'e7000000-0000-4000-8000-000000000002',
  'e7000000-0000-4000-8000-000000000001',
  'dept_tgdept_batch_99',
  null
);

-- 5. Test ATTENDANCE MARKED: Create attendance session and record for Student A
-- Setup course and semester course for attendance
insert into classmate.courses(id, department_id, course_code, course_title, credit, course_type)
values ('e7000000-0000-4000-8000-000000000060', 'e7000000-0000-4000-8000-000000000001', 'CSE-9901', 'Test Systems', 3.0, 'theory');

insert into classmate.semester_courses(id, semester_id, course_id)
values (
  'e7000000-0000-4000-8000-000000000061',
  (select id from classmate.semesters where batch_id = 'e7000000-0000-4000-8000-000000000002' and semester_number = 1),
  'e7000000-0000-4000-8000-000000000060'
);

insert into classmate.attendance_sessions(
  id, batch_id, semester_course_id, started_by, session_secret, status
) values (
  'e7000000-0000-4000-8000-000000000040',
  'e7000000-0000-4000-8000-000000000002',
  'e7000000-0000-4000-8000-000000000061',
  'e7000000-0000-4000-8000-000000000011',
  'secret99',
  'open'
);

insert into classmate.attendance_records(
  id, session_id, student_id, method, confidence
) values (
  'e7000000-0000-4000-8000-000000000041',
  'e7000000-0000-4000-8000-000000000040',
  'e7000000-0000-4000-8000-000000000012',
  'rfid',
  'normal'
);

-- Attendance outbox row with NULL recipient_profile_id (as previously inserted)
insert into classmate.notification_outbox(
  id, kind, record_id, batch_id, department_id, routing_label, recipient_profile_id
) values (
  'e7000000-0000-4000-8000-000000000042',
  'attendance_marked',
  'e7000000-0000-4000-8000-000000000041',
  'e7000000-0000-4000-8000-000000000002',
  'e7000000-0000-4000-8000-000000000001',
  'student_CE99002',
  null
);

-- 6. Test BROADCAST KIND (e.g. notice or attendance_open):
insert into classmate.notification_outbox(
  id, kind, record_id, batch_id, department_id, routing_label
) values (
  'e7000000-0000-4000-8000-000000000050',
  'attendance_open',
  'e7000000-0000-4000-8000-000000000040',
  'e7000000-0000-4000-8000-000000000002',
  'e7000000-0000-4000-8000-000000000001',
  'dept_tgdept_batch_99'
);

-- Execute assertions in PL/pgSQL block
do $$
declare
  token_hash_a text := encode(extensions.digest('fcm-token-student-a-99', 'sha256'), 'hex');
  token_hash_b text := encode(extensions.digest('fcm-token-student-b-99', 'sha256'), 'hex');
begin
  -- Assert 4a: Recipients must be ONLY Student A (count = 1)
  perform pg_temp.assert_eq(
    (select count(*) from classmate.notification_recipients('e7000000-0000-4000-8000-000000000030')),
    1::bigint,
    'Deposit with recipient_profile_id must return exactly 1 recipient'
  );

  perform pg_temp.assert_eq(
    (select profile_id from classmate.notification_recipients('e7000000-0000-4000-8000-000000000030')),
    'e7000000-0000-4000-8000-000000000012'::uuid,
    'Deposit recipient must be Student A'
  );

  -- Assert 4b: Even when recipient_profile_id is NULL, it MUST NOT broadcast to batch! Must return only Student A!
  perform pg_temp.assert_eq(
    (select count(*) from classmate.notification_recipients('e7000000-0000-4000-8000-000000000031')),
    1::bigint,
    'Deposit with NULL recipient_profile_id must fallback to transaction student and return exactly 1 recipient'
  );

  perform pg_temp.assert_eq(
    (select profile_id from classmate.notification_recipients('e7000000-0000-4000-8000-000000000031')),
    'e7000000-0000-4000-8000-000000000012'::uuid,
    'Deposit recipient must still be Student A'
  );

  -- Assert 5: Attendance marked MUST return ONLY Student A (count = 1), NOT Student B!
  perform pg_temp.assert_eq(
    (select count(*) from classmate.notification_recipients('e7000000-0000-4000-8000-000000000042')),
    1::bigint,
    'Attendance marked must return exactly 1 recipient'
  );

  perform pg_temp.assert_eq(
    (select profile_id from classmate.notification_recipients('e7000000-0000-4000-8000-000000000042')),
    'e7000000-0000-4000-8000-000000000012'::uuid,
    'Attendance marked recipient must be Student A'
  );

  -- Assert 6: Broadcast kind MUST return both registered students in batch 99 (Student A and Student B)
  perform pg_temp.assert_eq(
    (select count(*) from classmate.notification_recipients('e7000000-0000-4000-8000-000000000050')),
    2::bigint,
    'attendance_open broadcast must return all batch students with tokens'
  );

  -- Assert 7: Device allowed checks
  -- Student A device allowed for their deposit? YES
  perform pg_temp.assert_eq(
    classmate.notification_device_allowed('e7000000-0000-4000-8000-000000000030', 'e7000000-0000-4000-8000-000000000012', token_hash_a),
    true,
    'Student A device must be allowed for own deposit'
  );

  -- Student B device allowed for Student A deposit? NO!
  perform pg_temp.assert_eq(
    classmate.notification_device_allowed('e7000000-0000-4000-8000-000000000030', 'e7000000-0000-4000-8000-000000000013', token_hash_b),
    false,
    'Student B device must NOT be allowed for Student A deposit'
  );

  -- Student B device allowed for Student A attendance? NO!
  perform pg_temp.assert_eq(
    classmate.notification_device_allowed('e7000000-0000-4000-8000-000000000042', 'e7000000-0000-4000-8000-000000000013', token_hash_b),
    false,
    'Student B device must NOT be allowed for Student A attendance'
  );
end $$;

rollback;
