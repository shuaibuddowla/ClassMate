import pathlib
import sys
import time
import hmac
import hashlib
import json

root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

def test_attendance_suite(ref, label):
    print(f"\n==========================================")
    print(f"RUNNING ATTENDANCE TEST SUITE ON {label.upper()} ({ref})")
    print(f"==========================================")

    # 1. Find a test slot and student
    slot_res = query(ref, """
        select rs.id as slot_id, sc.id as offering_id, s.batch_id, sc.course_id, c.course_title
        from classmate.routine_slots rs
        join classmate.semester_courses sc on sc.id = rs.semester_course_id
        join classmate.courses c on c.id = sc.course_id
        join classmate.semesters s on s.id = sc.semester_id
        where s.status = 'active' and sc.attendance_enabled = true
        limit 1;
    """)
    if not slot_res:
        # If no attendance_enabled course, enable one temporarily or find any slot
        slot_res = query(ref, """
            select rs.id as slot_id, sc.id as offering_id, s.batch_id, sc.course_id, c.course_title
            from classmate.routine_slots rs
            join classmate.semester_courses sc on sc.id = rs.semester_course_id
            join classmate.courses c on c.id = sc.course_id
            join classmate.semesters s on s.id = sc.semester_id
            where s.status = 'active'
            limit 1;
        """)
        if slot_res:
            off_id = slot_res[0]['offering_id']
            query(ref, f"update classmate.semester_courses set attendance_enabled = true where id = '{off_id}';")
            print(f"Enabled attendance on course offering {off_id}")

    if not slot_res:
        print(f"Skipping {label}: No routine slots in this environment.")
        return
    slot_id = slot_res[0]['slot_id']
    batch_id = slot_res[0]['batch_id']
    course_title = slot_res[0]['course_title']
    print(f"Found routine slot: {slot_id} ({course_title}) in batch {batch_id}")

    # 2. Find an active student in this batch or admin
    student_res = query(ref, f"""
        select id, email, full_name, student_id, role
        from classmate.profiles
        where batch_id = '{batch_id}' and verification_status = 'active' and role in ('student', 'admin')
        limit 1;
    """)
    assert student_res, f"No student/admin found in batch {batch_id}"
    student_id = student_res[0]['id']
    student_name = student_res[0]['full_name']
    roll = student_res[0]['student_id']
    print(f"Test Student: {student_name} (Roll: {roll}, ID: {student_id})")

    # 3. Test starting an attendance session
    # We call start_attendance_session under admin/owner privileges
    start_sql = f"""
        select classmate.start_attendance_session('{slot_id}'::uuid) as result;
    """
    start_res = query(ref, start_sql)
    assert start_res, "start_attendance_session returned empty"
    res_obj = start_res[0]['result']
    if isinstance(res_obj, str):
        res_obj = json.loads(res_obj)
    
    session_id = res_obj['session_id']
    session_secret = res_obj['session_secret']
    status = res_obj['status']
    print(f"Session started/resumed: {session_id}")
    print(f"Secret: {session_secret[:8]}... (len: {len(session_secret)})")
    print(f"Status: {status}")
    assert status == 'open', f"Expected status 'open', got {status}"
    assert len(session_secret) >= 16, "Secret is too short"

    # 4. Generate dynamic HMAC token for current time window
    now_epoch = int(time.time())
    current_window = now_epoch // 15
    token = hmac.new(session_secret.encode('utf-8'), str(current_window).encode('utf-8'), hashlib.sha256).hexdigest()
    print(f"Generated QR HMAC for window {current_window}: {token[:12]}...")

    # 5. Test mark_attendance with LIVE token (Simulate student scan)
    # We simulate caller_student_id as the student
    mark_sql = f"""
        set local "request.jwt.claim.sub" = '{student_id}';
        set local role = 'authenticated';
        select classmate.mark_attendance(
            '{session_id}'::uuid,
            '{token}',
            {current_window}::bigint
        ) as mark_result;
    """
    mark_res = query(ref, mark_sql)
    assert mark_res, "mark_attendance failed to return result"
    mark_obj = mark_res[0]['mark_result']
    if isinstance(mark_obj, str):
        mark_obj = json.loads(mark_obj)
    print(f"Mark attendance result: {mark_obj}")
    assert mark_obj.get('success') is True, f"Scan failed: {mark_obj}"
    assert mark_obj.get('status') in ('present', 'late_recorded'), f"Unexpected status: {mark_obj}"

    # 6. Test duplicate scan (idempotent, must not crash)
    dup_res = query(ref, mark_sql)
    dup_obj = dup_res[0]['mark_result']
    if isinstance(dup_obj, str):
        dup_obj = json.loads(dup_obj)
    assert dup_obj.get('success') is True, f"Duplicate scan failed: {dup_obj}"
    print("Duplicate scan handled smoothly (idempotent)")

    # 7. Test expired token (e.g. 5 windows = 75 seconds ago) -> MUST BE REJECTED!
    old_window = current_window - 5
    old_token = hmac.new(session_secret.encode('utf-8'), str(old_window).encode('utf-8'), hashlib.sha256).hexdigest()
    expired_sql = f"""
        set local "request.jwt.claim.sub" = '{student_id}';
        set local role = 'authenticated';
        select classmate.mark_attendance(
            '{session_id}'::uuid,
            '{old_token}',
            {old_window}::bigint
        ) as expired_result;
    """
    expired_rejected = False
    try:
        query(ref, expired_sql)
    except Exception as e:
        expired_rejected = True
        print(f"Expired token correctly rejected: {e}")
    assert expired_rejected, "SECURITY ERROR: Expired token was accepted!"

    # 8. Test get_session_summary to verify student shows up as PRESENT
    summary_sql = f"select classmate.get_session_summary('{session_id}'::uuid) as summary;"
    sum_res = query(ref, summary_sql)
    assert sum_res, "get_session_summary returned empty"
    sum_obj = sum_res[0]['summary']
    if isinstance(sum_obj, str):
        sum_obj = json.loads(sum_obj)
    roster = sum_obj.get('roster', [])
    matched_student = next((s for s in roster if s['student_id'] == student_id), None)
    assert matched_student is not None, "Student not found in session roster"
    assert matched_student['status'] == 'present', f"Expected student to be present, got {matched_student['status']}"
    print(f"Roster check: {matched_student['full_name']} ({matched_student['roll_number']}) is PRESENT in session summary")

    # 9. Test active_attendance_sessions RPC
    active_sql = f"select * from classmate.active_attendance_sessions('{batch_id}'::uuid);"
    active_res = query(ref, active_sql)
    assert active_res, "active_attendance_sessions returned empty"
    active_match = next((s for s in active_res if s['session_id'] == session_id), None)
    assert active_match is not None, "Session not in active_attendance_sessions"
    assert active_match['status'] == 'open', "Active session not open"
    print(f"Active session found via timetable RPC with present count: {active_match['present_count']}")

    print(f"ALL TESTS PASSED ON {label.upper()}!\n")

if __name__ == '__main__':
    for ref, label in [(STAGING, 'staging'), (PRODUCTION, 'production')]:
        test_attendance_suite(ref, label)
