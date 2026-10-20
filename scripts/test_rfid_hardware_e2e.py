import pathlib
import sys
import json
import time

root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING

def run_rfid_test_suite():
    print("=" * 60)
    print("RUNNING HARDWARE RFID ATTENDANCE E2E TEST SUITE (STAGING)")
    print("=" * 60)

    # 1. Verify Attendance Device Exists
    devices = query(STAGING, "select * from classmate.attendance_devices where id = 'ESP32-READER-01';")
    assert devices, "Default device ESP32-READER-01 not found!"
    print(f"[PASS] Hardware device registered: {devices[0]['id']} ({devices[0]['label']})")

    # 2. Find an active routine slot & course offering
    slot_res = query(STAGING, """
        select rs.id as slot_id, sc.id as offering_id, s.batch_id, sc.course_id, c.course_code, c.course_title
        from classmate.routine_slots rs
        join classmate.semester_courses sc on sc.id = rs.semester_course_id
        join classmate.courses c on c.id = sc.course_id
        join classmate.semesters s on s.id = sc.semester_id
        where s.status = 'active'
        limit 1;
    """)
    assert slot_res, "No active routine slot found on staging"
    slot = slot_res[0]
    slot_id = slot['slot_id']
    batch_id = slot['batch_id']
    course_code = slot['course_code']
    print(f"[PASS] Found active course: {course_code} - {slot['course_title']} (Slot: {slot_id})")

    # 3. Find an active test student
    student_res = query(STAGING, f"""
        select id, full_name, student_id, role
        from classmate.profiles
        where batch_id = '{batch_id}' and verification_status = 'active'
        limit 1;
    """)
    assert student_res, f"No active student found in batch {batch_id}"
    student = student_res[0]
    student_id = student['id']
    student_name = student['full_name']
    roll = student['student_id']
    print(f"[PASS] Test Student: {student_name} (Roll: {roll}, ID: {student_id})")

    # 4. Ensure an open attendance session exists
    sess_res = query(STAGING, f"""
        select id, status from classmate.attendance_sessions
        where routine_slot_id = '{slot_id}' and status = 'open'
        limit 1;
    """)
    if not sess_res:
        start_res = query(STAGING, f"select classmate.start_attendance_session('{slot_id}'::uuid) as result;")
        assert start_res, "Failed to start attendance session"
        sess_res = query(STAGING, f"""
            select id, status from classmate.attendance_sessions
            where routine_slot_id = '{slot_id}' and status = 'open'
            limit 1;
        """)
    session_id = sess_res[0]['id']
    print(f"[PASS] Open Attendance Session: {session_id}")

    test_card_uid = "e2e99aa1"

    # Reset any previous state for clean test run
    query(STAGING, f"update classmate.profiles set rfid_card_uid = null where lower(rfid_card_uid) = '{test_card_uid}';")
    query(STAGING, f"delete from classmate.unregistered_rfid_scans where lower(card_uid) = '{test_card_uid}';")
    query(STAGING, f"delete from classmate.attendance_records where session_id = '{session_id}' and student_id = '{student_id}';")
    query(STAGING, f"update classmate.attendance_devices set active_session_id = null where id = 'ESP32-READER-01';")

    # ------------------------------------------------------------------------
    # TEST 1: Scan with Unregistered Card (Without Active Session Linked)
    # ------------------------------------------------------------------------
    scan1 = query(STAGING, f"select classmate.process_rfid_scan('{test_card_uid}', 'ESP32-READER-01') as res;")[0]['res']
    print("\n[TEST 1] Scan with Unregistered Card:")
    print("  Result:", scan1)
    assert scan1['status'] == 'UNREGISTERED', f"Expected UNREGISTERED, got {scan1['status']}"
    unreg_rows = query(STAGING, f"select * from classmate.unregistered_rfid_scans where lower(card_uid) = '{test_card_uid}';")
    assert unreg_rows, "Expected record in unregistered_rfid_scans table"
    print("  [OK] Unregistered card logged to triage queue.")

    # ------------------------------------------------------------------------
    # TEST 2: Link Hardware Device to Session
    # ------------------------------------------------------------------------
    print("\n[TEST 2] Link Device to Session:")
    link_res = query(STAGING, f"""
        update classmate.attendance_devices
        set active_session_id = '{session_id}', updated_at = now()
        where id = 'ESP32-READER-01'
        returning id, active_session_id;
    """)
    assert link_res and link_res[0]['active_session_id'] == session_id
    print(f"  [OK] ESP32-READER-01 linked to session {session_id}")

    # ------------------------------------------------------------------------
    # TEST 3: Enroll Card to Student
    # ------------------------------------------------------------------------
    print("\n[TEST 3] Enroll Card to Student:")
    enroll_res = query(STAGING, f"""
        update classmate.profiles
        set rfid_card_uid = '{test_card_uid}'
        where id = '{student_id}'
        returning id, full_name, rfid_card_uid;
    """)
    assert enroll_res and enroll_res[0]['rfid_card_uid'] == test_card_uid
    # Mark triage resolved
    query(STAGING, f"update classmate.unregistered_rfid_scans set resolved = true where lower(card_uid) = '{test_card_uid}';")
    print(f"  [OK] Card {test_card_uid} assigned to {student_name} ({roll})")

    # ------------------------------------------------------------------------
    # TEST 4: Scan with Enrolled Card (Success / CHECK_IN)
    # ------------------------------------------------------------------------
    print("\n[TEST 4] Scan with Enrolled Card (CHECK_IN):")
    scan2 = query(STAGING, f"select classmate.process_rfid_scan('{test_card_uid}', 'ESP32-READER-01') as res;")[0]['res']
    print("  Result:", scan2)
    assert scan2['status'] == 'CHECK_IN', f"Expected CHECK_IN, got {scan2['status']}"
    assert scan2['user_name'] == student_name, f"Expected {student_name}, got {scan2['user_name']}"
    assert scan2['identifier'] == roll, f"Expected {roll}, got {scan2['identifier']}"

    # Verify record in attendance_records
    rec = query(STAGING, f"""
        select * from classmate.attendance_records
        where session_id = '{session_id}' and student_id = '{student_id}';
    """)
    assert rec, "Attendance record not found in attendance_records table!"
    assert rec[0]['method'] == 'rfid', f"Expected method 'rfid', got {rec[0]['method']}"
    assert rec[0]['confidence'] == 'normal', f"Expected confidence 'normal', got {rec[0]['confidence']}"
    print(f"  [OK] Attendance record verified in DB with method='{rec[0]['method']}', confidence='{rec[0]['confidence']}'")

    # ------------------------------------------------------------------------
    # TEST 5: Retap / Idempotent Debounce
    # ------------------------------------------------------------------------
    print("\n[TEST 5] Idempotent Retap:")
    scan3 = query(STAGING, f"select classmate.process_rfid_scan('{test_card_uid}', 'ESP32-READER-01') as res;")[0]['res']
    assert scan3['status'] == 'CHECK_IN'
    print("  [OK] Retap handled cleanly without constraint violations.")

    # ------------------------------------------------------------------------
    # TEST 6: Unlinked Device / Closed Session Scan
    # ------------------------------------------------------------------------
    print("\n[TEST 6] Device Unlinked from Session:")
    query(STAGING, "update classmate.attendance_devices set active_session_id = null where id = 'ESP32-READER-01';")
    scan4 = query(STAGING, f"select classmate.process_rfid_scan('{test_card_uid}', 'ESP32-READER-01') as res;")[0]['res']
    print("  Result:", scan4)
    assert scan4['status'] == 'NO_SESSION', f"Expected NO_SESSION, got {scan4['status']}"
    print("  [OK] Correctly reported NO_SESSION when no class session is linked.")

    # Clean up test card binding
    query(STAGING, f"update classmate.profiles set rfid_card_uid = null where id = '{student_id}';")
    query(STAGING, f"delete from classmate.unregistered_rfid_scans where lower(card_uid) = '{test_card_uid}';")

    print("\n" + "=" * 60)
    print("ALL 6 HARDWARE RFID TESTS PASSED SUCCESSFULLY! [PASS]")
    print("=" * 60)

if __name__ == '__main__':
    run_rfid_test_suite()
