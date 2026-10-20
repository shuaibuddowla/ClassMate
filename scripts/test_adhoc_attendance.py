import sys, pathlib
root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING

print("=== TESTING AD-HOC ATTENDANCE & DEVICE HEARTBEAT ON STAGING ===")

# 1. Device status initially
dev_status = query(STAGING, "select classmate.get_device_active_session('ESP32-READER-01') as dev;")
print("1. Initial Device Status:", dev_status[0]['dev'])

# 2. Get an active semester course
course = query(STAGING, """
    select sc.id, sc.semester_id, c.course_code, c.course_title, s.batch_id
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    join classmate.semesters s on s.id = sc.semester_id
    limit 1;
""")
print("2. Active course found:", course[0])
course_id = course[0]['id']

# 3. Start ad-hoc session
start_res = query(STAGING, f"select classmate.start_adhoc_attendance_session('{course_id}', 'ESP32-READER-01') as sess;")
print("3. start_adhoc_attendance_session result:", start_res[0]['sess'])
session_id = start_res[0]['sess']['session_id']

# 4. Check device status now (Simulating ESP32 GET /api/scan?device_id=ESP32-READER-01)
active_dev = query(STAGING, "select classmate.get_device_active_session('ESP32-READER-01') as dev;")
print("4. Active Device Heartbeat Response:", active_dev[0]['dev'])

# 5. Simulate student tap with enrolled card
query(STAGING, "update classmate.profiles set rfid_card_uid = 'E2E99AA1' where id = '0818cf4f-9679-413c-9c5c-171a010b6c5b';")
scan_res = query(STAGING, "select classmate.process_rfid_scan('e2e99aa1', 'ESP32-READER-01') as scan;")
print("5. Student RFID Scan Response:", scan_res[0]['scan'])

# 6. Check student_my_attendance for that student
my_att = query(STAGING, f"select classmate.student_my_attendance('{course[0]['batch_id']}') as att;")
print(f"6. student_my_attendance returned {len(my_att[0]['att']['courses'])} courses (only held > 0):")
for c in my_att[0]['att']['courses']:
    print(f"   - {c['course_code']}: held={c['classes_held']}, attended={c['classes_attended']}")

# 7. Check outbox for scan notification
outbox = query(STAGING, "select id, kind, record_id, routing_label, delivered_at from classmate.notification_outbox where kind in ('attendance_open', 'attendance_marked') order by created_at desc limit 2;")
print("7. Notification outbox:", outbox)

# 8. Close session
close_res = query(STAGING, f"select classmate.close_adhoc_attendance_session('{session_id}') as res;")
print("8. close_adhoc_attendance_session result:", close_res[0]['res'])

# 9. Verify device went to sleep
sleep_dev = query(STAGING, "select classmate.get_device_active_session('ESP32-READER-01') as dev;")
print("9. Device Status after Close (Must be SLEEP):", sleep_dev[0]['dev'])
