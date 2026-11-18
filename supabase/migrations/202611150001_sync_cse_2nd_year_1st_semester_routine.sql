-- ============================================================================
-- Migration: 202611150001_sync_cse_2nd_year_1st_semester_routine.sql
-- Description: Synchronize CSE 2nd Year 1st Semester (Batch 22) routine slots
--              with official department routine effective from 26/09/2026.
--              1. Correct Sunday CSE2105 start time to 11:00 AM (10:00-10:50 is free).
--              2. Populate teacher_record_id and teacher_name on all 18 routine slots.
--              3. Ensure slot types ('class' vs 'lab') match course structures.
-- ============================================================================

begin;

-- 1. Sunday: CSE-2105 (COA) at 11:00 AM - 11:50 AM in Room 338 by Md. Hadifur Rahman (MHR)
update classmate.routine_slots
set start_time = '11:00:00',
    end_time = '11:50:00',
    room = '338',
    type = 'class',
    teacher_record_id = 'f7375818-1918-40e8-b83b-78f762df0946',
    teacher_name = 'Md. Hadifur Rahman'
where id = 'dacccfd4-16fc-484b-94cb-16a67a7abd33';

-- 2. Sunday: CSE-2103 (DSA) at 12:00 PM - 12:50 PM in Room 341 by Dr. Mehedi Hasan Talukder (MHT)
update classmate.routine_slots
set start_time = '12:00:00',
    end_time = '12:50:00',
    room = '341',
    type = 'class',
    teacher_record_id = 'd1990c49-2ad9-43d2-a497-413bffd388d0',
    teacher_name = 'Dr. Mehedi Hasan Talukder'
where id = '4a923aea-2edf-4e2e-a9a8-403deaa6bc36';

-- 3. Sunday: CSE-2104 (DSA Lab) at 01:45 PM - 03:10 PM in Lab-02 by Dr. Mehedi Hasan Talukder (MHT)
update classmate.routine_slots
set start_time = '13:45:00',
    end_time = '15:10:00',
    room = 'Lab-02',
    type = 'lab',
    teacher_record_id = 'd1990c49-2ad9-43d2-a497-413bffd388d0',
    teacher_name = 'Dr. Mehedi Hasan Talukder'
where id = 'b999dc4d-d9e9-48d7-8a4d-c0d4cccb99c2';

-- 4. Sunday: CSE-2101 (OOP) at 03:15 PM - 03:55 PM in Room 338 by Dr. Md. Sazzad Hossain (MSH)
update classmate.routine_slots
set start_time = '15:15:00',
    end_time = '15:55:00',
    room = '338',
    type = 'class',
    teacher_record_id = '0d50c02e-397c-4671-a5bf-6e181e445b26',
    teacher_name = 'Dr. Md. Sazzad Hossain'
where id = '726e9ee1-ca46-4b21-8578-18729e18abbf';

-- 5. Monday: CSE-2107 (Matrix & Linear Algebra) at 10:00 AM - 10:50 AM in Room 338 by Dr. Mohammad Mokaddes Ali (MMA)
update classmate.routine_slots
set start_time = '10:00:00',
    end_time = '10:50:00',
    room = '338',
    type = 'class',
    teacher_record_id = '87e17bde-7bb4-4f06-a677-971cb832dc58',
    teacher_name = 'Dr. Mohammad Mokaddes Ali'
where id = '2b6b3152-dbdd-4e26-ad32-e732c52a6adc';

-- 6. Monday: CSE-2103 (DSA) at 12:00 PM - 12:50 PM in Room 341 by Dr. Mehedi Hasan Talukder (MHT)
update classmate.routine_slots
set start_time = '12:00:00',
    end_time = '12:50:00',
    room = '341',
    type = 'class',
    teacher_record_id = 'd1990c49-2ad9-43d2-a497-413bffd388d0',
    teacher_name = 'Dr. Mehedi Hasan Talukder'
where id = '27f80568-bf69-4768-92e7-9c0d4dfe2d59';

-- 7. Monday: CSE-2101 (OOP) at 01:45 PM - 02:25 PM in Room 338 by Dr. Md. Sazzad Hossain (MSH)
update classmate.routine_slots
set start_time = '13:45:00',
    end_time = '14:25:00',
    room = '338',
    type = 'class',
    teacher_record_id = '0d50c02e-397c-4671-a5bf-6e181e445b26',
    teacher_name = 'Dr. Md. Sazzad Hossain'
where id = 'd305bea8-1bb6-4d02-b70a-a846050a9a22';

-- 8. Monday: CSE-2102 (OOP Lab) at 02:30 PM - 03:55 PM in Lab-01 by Dr. Md. Sazzad Hossain (MSH)
update classmate.routine_slots
set start_time = '14:30:00',
    end_time = '15:55:00',
    room = 'Lab-01',
    type = 'lab',
    teacher_record_id = '0d50c02e-397c-4671-a5bf-6e181e445b26',
    teacher_name = 'Dr. Md. Sazzad Hossain'
where id = 'c7b7c4d1-229a-4dc8-af7e-f0627ecb066a';

-- 9. Tuesday: CSE-2101 (OOP) at 10:00 AM - 10:50 AM in Room 339 by Dr. Md. Sazzad Hossain (MSH)
update classmate.routine_slots
set start_time = '10:00:00',
    end_time = '10:50:00',
    room = '339',
    type = 'class',
    teacher_record_id = '0d50c02e-397c-4671-a5bf-6e181e445b26',
    teacher_name = 'Dr. Md. Sazzad Hossain'
where id = 'e15acae8-bdef-486b-983e-5c94d414a314';

-- 10. Tuesday: CSE-2105 (COA) at 11:00 AM - 11:50 AM in Room 339 by Md. Hadifur Rahman (MHR)
update classmate.routine_slots
set start_time = '11:00:00',
    end_time = '11:50:00',
    room = '339',
    type = 'class',
    teacher_record_id = 'f7375818-1918-40e8-b83b-78f762df0946',
    teacher_name = 'Md. Hadifur Rahman'
where id = '06d0a97f-be40-406f-b36e-2b64648047eb';

-- 11. Tuesday: CSE-2103 (DSA) at 12:00 PM - 12:50 PM in Room 339 by Dr. Mehedi Hasan Talukder (MHT)
update classmate.routine_slots
set start_time = '12:00:00',
    end_time = '12:50:00',
    room = '339',
    type = 'class',
    teacher_record_id = 'd1990c49-2ad9-43d2-a497-413bffd388d0',
    teacher_name = 'Dr. Mehedi Hasan Talukder'
where id = 'bdf9ee2e-a7d7-4cf1-8df5-c0d55da7fa66';

-- 12. Tuesday: CSE-2102 (OOP Lab) at 01:45 PM - 03:10 PM in Lab-01 by Dr. Md. Sazzad Hossain (MSH)
update classmate.routine_slots
set start_time = '13:45:00',
    end_time = '15:10:00',
    room = 'Lab-01',
    type = 'lab',
    teacher_record_id = '0d50c02e-397c-4671-a5bf-6e181e445b26',
    teacher_name = 'Dr. Md. Sazzad Hossain'
where id = '868803f9-713a-432e-89af-3b73a044c229';

-- 13. Wednesday: CSE-2107 (Matrix & Linear Algebra) at 10:00 AM - 10:50 AM in Room 341 by Dr. Mohammad Mokaddes Ali (MMA)
update classmate.routine_slots
set start_time = '10:00:00',
    end_time = '10:50:00',
    room = '341',
    type = 'class',
    teacher_record_id = '87e17bde-7bb4-4f06-a677-971cb832dc58',
    teacher_name = 'Dr. Mohammad Mokaddes Ali'
where id = 'b710c579-7c2a-4d95-9df9-3ba65698d74a';

-- 14. Wednesday: CSE-2111 (Financial & Managerial Accounting) at 11:00 AM - 12:50 PM in Room 338 by Dr. Md. Abdul Kuddus (MAK)
update classmate.routine_slots
set start_time = '11:00:00',
    end_time = '12:50:00',
    room = '338',
    type = 'class',
    teacher_record_id = '34c6b79c-1336-415e-9cd0-f7068a4f7f66',
    teacher_name = 'Dr. Md. Abdul Kuddus'
where id = '9da04278-f511-456e-9cd0-b4e133f5e864';

-- 15. Wednesday: CSE-2109 (Sociology & Economics) at 02:30 PM - 03:10 PM in Room 340 by Mehedi Hasan (MH)
update classmate.routine_slots
set start_time = '14:30:00',
    end_time = '15:10:00',
    room = '340',
    type = 'class',
    teacher_record_id = '21a3d92a-7cf2-42af-8f06-a73ccdabea26',
    teacher_name = 'Mehedi Hasan'
where id = 'c6f2eb6d-b094-429f-831a-e8df70193813';

-- 16. Saturday: CSE-2104 (DSA Lab) at 10:00 AM - 11:50 AM in Lab-02 by Dr. Mehedi Hasan Talukder (MHT)
update classmate.routine_slots
set start_time = '10:00:00',
    end_time = '11:50:00',
    room = 'Lab-02',
    type = 'lab',
    teacher_record_id = 'd1990c49-2ad9-43d2-a497-413bffd388d0',
    teacher_name = 'Dr. Mehedi Hasan Talukder'
where id = 'c3dcf239-c67a-4a87-9f26-843e396ed56f';

-- 17. Saturday: CSE-2107 (Matrix & Linear Algebra) at 12:00 PM - 12:50 PM in Room 338 by Dr. Mohammad Mokaddes Ali (MMA)
update classmate.routine_slots
set start_time = '12:00:00',
    end_time = '12:50:00',
    room = '338',
    type = 'class',
    teacher_record_id = '87e17bde-7bb4-4f06-a677-971cb832dc58',
    teacher_name = 'Dr. Mohammad Mokaddes Ali'
where id = 'ba9b4660-b0a3-4de5-8bd4-b9e279dbf095';

-- 18. Saturday: CSE-2109 (Sociology & Economics) at 02:30 PM - 03:10 PM in Room 340 by Mehedi Hasan (MH)
update classmate.routine_slots
set start_time = '14:30:00',
    end_time = '15:10:00',
    room = '340',
    type = 'class',
    teacher_record_id = '21a3d92a-7cf2-42af-8f06-a73ccdabea26',
    teacher_name = 'Mehedi Hasan'
where id = '73c51f34-173e-4881-976b-c0335c9a30e5';

commit;
