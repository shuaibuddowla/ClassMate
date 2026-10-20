import pathlib
import sys
import json

root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING

def seed_staging():
    print("Seeding Staging database with test fixtures...")
    
    # 1. Existing Department
    dept_res = query(STAGING, "select id from classmate.departments where code = 'cse';")
    dept_id = dept_res[0]['id']
    print("[OK] Department CSE:", dept_id)

    # 2. Batch (CSE-22)
    batch_res = query(STAGING, "select id from classmate.batches where batch_number = 22;")
    batch_id = batch_res[0]['id']
    print("[OK] Batch CSE-22:", batch_id)

    # 3. Course: CSE 2105
    course_res = query(STAGING, "select id from classmate.courses where course_code = 'CSE 2105';")
    course_id = course_res[0]['id']
    print("[OK] Course: CSE 2105:", course_id)

    # 4. Activate Semester 4
    query(STAGING, f"""
        update classmate.semesters
        set status = 'active'
        where batch_id = '{batch_id}' and semester_number = 4;
    """)
    sem_res = query(STAGING, f"""
        select id from classmate.semesters
        where batch_id = '{batch_id}' and semester_number = 4;
    """)
    sem_id = sem_res[0]['id']
    print("[OK] Activated Semester 4:", sem_id)

    # 5. Semester Course offering
    sc_res = query(STAGING, f"""
        insert into classmate.semester_courses (id, semester_id, course_id, attendance_enabled)
        values ('55555555-5555-5555-5555-555555555555', '{sem_id}', '{course_id}', true)
        on conflict (semester_id, course_id) do update set attendance_enabled = true
        returning id;
    """)
    if not sc_res:
        sc_res = query(STAGING, f"select id from classmate.semester_courses where semester_id = '{sem_id}' and course_id = '{course_id}';")
    sc_id = sc_res[0]['id']
    print("[OK] Semester Course Offering:", sc_id)

    # 6. Routine Slot
    slot_res = query(STAGING, f"""
        insert into classmate.routine_slots (id, semester_course_id, day_of_week, start_time, end_time, room, type)
        values ('66666666-6666-6666-6666-666666666666', '{sc_id}', 2, '10:00:00', '11:30:00', 'Room 304', 'class')
        on conflict (id) do nothing
        returning id;
    """)
    if not slot_res:
        slot_res = query(STAGING, f"select id from classmate.routine_slots where semester_course_id = '{sc_id}';")
    slot_id = slot_res[0]['id']
    print("[OK] Routine Slot:", slot_id)

    # 7. Update Admin Profile
    query(STAGING, f"""
        update classmate.profiles
        set batch_id = '{batch_id}', student_id = '2101001', verification_status = 'active'
        where email = 'ce25045@mbstu.ac.bd';
    """)
    print("[OK] Profile updated for test student (2101001)")

if __name__ == '__main__':
    seed_staging()
