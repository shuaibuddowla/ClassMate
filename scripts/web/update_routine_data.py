import sys, json
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "capacity"))
from platform_access import query, PRODUCTION

OWNER_ID = "27ce19e9-ea97-449b-a0ef-dc18d7b7f4d9"
CSE_DEPT_ID = "d63bb85b-13bd-4da6-b905-12d622ebd3f5"
B22_S3_ID = "56056dc8-5c95-40d5-95b5-162a6ff4cd49"

def run_migration():
    # 1. Insert new courses if not existing
    insert_courses_sql = f"""
    insert into classmate.courses (department_id, course_code, course_title, course_type, credit)
    values
      ('{CSE_DEPT_ID}', 'CSE2109', 'Sociology & Economics', 'theory', 2.00),
      ('{CSE_DEPT_ID}', 'CSE2111', 'Financial and Managerial Accounting', 'theory', 2.00),
      ('{CSE_DEPT_ID}', 'CSE2114', 'Software Development Project-I & Industrial Tour', 'lab', 0.75)
    on conflict (department_id, course_code) do update
      set course_title = excluded.course_title,
          course_type = excluded.course_type,
          credit = excluded.credit;
    """
    print("Inserting courses...")
    query(PRODUCTION, insert_courses_sql)

    # Update existing course details to match official names & credits
    update_courses_sql = f"""
    update classmate.courses set course_title = 'Object Oriented Programming', credit = 3.00 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2101';
    update classmate.courses set course_title = 'Object Oriented Programming Lab', credit = 1.50 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2102';
    update classmate.courses set course_title = 'Data Structures and Algorithms', credit = 3.00 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2103';
    update classmate.courses set course_title = 'Data Structures and Algorithms Lab', credit = 1.50 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2104';
    update classmate.courses set course_title = 'Computer Organization and Architecture', credit = 3.00 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2105';
    update classmate.courses set course_title = 'Matrix and Linear Algebra', credit = 3.00 where department_id = '{CSE_DEPT_ID}' and course_code = 'CSE2107';
    """
    print("Updating existing course metadata...")
    query(PRODUCTION, update_courses_sql)

    # 2. Insert new teacher records in teacher_directory
    insert_teachers_sql = f"""
    insert into classmate.teacher_directory (department_id, full_name, created_by)
    values
      ('{CSE_DEPT_ID}', 'Md. Mahbubur Rahman', '{OWNER_ID}'),
      ('{CSE_DEPT_ID}', 'Dr. Md. Mahfuzur Rahman', '{OWNER_ID}'),
      ('{CSE_DEPT_ID}', 'Dr. Md. Abdul Kuddus', '{OWNER_ID}'),
      ('{CSE_DEPT_ID}', 'Mehedi Hasan', '{OWNER_ID}')
    on conflict do nothing;
    """
    print("Inserting teachers into directory...")
    query(PRODUCTION, insert_teachers_sql)

    # Fetch teacher IDs
    teachers_rows = query(PRODUCTION, f"select id, full_name from classmate.teacher_directory where department_id = '{CSE_DEPT_ID}';")
    teacher_map = {t["full_name"]: t["id"] for t in teachers_rows}
    print("Teacher Map:", teacher_map)

    # 3. Insert semester_courses for B22 S3
    insert_sc_sql = f"""
    insert into classmate.semester_courses (semester_id, course_id, teacher_record_id)
    select '{B22_S3_ID}', c.id,
      case
        when c.course_code in ('CSE2101', 'CSE2102') then '{teacher_map["Dr. Md. Sazzad Hossain"]}'::uuid
        when c.course_code in ('CSE2103', 'CSE2104') then '{teacher_map["Dr. Mehedi Hasan Talukder"]}'::uuid
        when c.course_code = 'CSE2105' then '{teacher_map["Dr. Md. Mahfuzur Rahman"]}'::uuid
        when c.course_code = 'CSE2107' then '{teacher_map["Md. Mahbubur Rahman"]}'::uuid
        when c.course_code = 'CSE2109' then '{teacher_map["Mehedi Hasan"]}'::uuid
        when c.course_code = 'CSE2111' then '{teacher_map["Dr. Md. Abdul Kuddus"]}'::uuid
        else null
      end
    from classmate.courses c
    where c.department_id = '{CSE_DEPT_ID}'
      and c.course_code in ('CSE2101', 'CSE2102', 'CSE2103', 'CSE2104', 'CSE2105', 'CSE2107', 'CSE2109', 'CSE2111', 'CSE2114')
    on conflict (semester_id, course_id) do update
      set teacher_record_id = excluded.teacher_record_id;
    """
    print("Upserting semester courses...")
    query(PRODUCTION, insert_sc_sql)

    # Fetch semester course IDs
    sc_rows = query(PRODUCTION, f"""
    select sc.id, c.course_code
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    where sc.semester_id = '{B22_S3_ID}';
    """)
    sc_map = {row["course_code"]: row["id"] for row in sc_rows}
    print("Semester Course Map:", sc_map)

    # 4. Clear old routine slots for Batch 22 Semester 3 and insert the 15 exact slots
    # First, delete existing routine slots
    del_slots_sql = f"""
    delete from classmate.routine_slots
    where semester_course_id in (
      select id from classmate.semester_courses where semester_id = '{B22_S3_ID}'
    );
    """
    print("Clearing old routine slots...")
    query(PRODUCTION, del_slots_sql)

    # Routine slots to insert:
    # (course_code, day_of_week, start_time, end_time, room, type)
    slots = [
        # Saturday (day 6)
        ("CSE2104", 6, "10:00:00", "11:50:00", "Lab-02", "lab"),
        ("CSE2107", 6, "12:00:00", "12:50:00", "338", "class"),
        ("CSE2109", 6, "14:30:00", "15:10:00", "340", "class"),

        # Sunday (day 0)
        ("CSE2105", 0, "10:00:00", "10:50:00", "338", "class"),
        ("CSE2103", 0, "12:00:00", "12:50:00", "341", "class"),
        ("CSE2104", 0, "13:45:00", "15:10:00", "Lab-02", "lab"),
        ("CSE2101", 0, "15:15:00", "15:55:00", "338", "class"),

        # Monday (day 1)
        ("CSE2107", 1, "10:00:00", "10:50:00", "338", "class"),
        ("CSE2103", 1, "12:00:00", "12:50:00", "341", "class"),
        ("CSE2101", 1, "13:45:00", "14:25:00", "338", "class"),
        ("CSE2102", 1, "14:30:00", "15:55:00", "Lab-01", "lab"),

        # Tuesday (day 2)
        ("CSE2101", 2, "09:00:00", "09:50:00", "339", "class"),
        ("CSE2105", 2, "10:00:00", "10:50:00", "339", "class"),
        ("CSE2103", 2, "11:00:00", "11:50:00", "339", "class"),
        ("CSE2102", 2, "13:45:00", "15:10:00", "Lab-01", "lab"),

        # Wednesday (day 3)
        ("CSE2107", 3, "10:00:00", "10:50:00", "341", "class"),
        ("CSE2111", 3, "11:00:00", "11:50:00", "338", "class"),
        ("CSE2109", 3, "14:30:00", "15:10:00", "340", "class"),
    ]

    values_str = ",\n".join([
        f"('{sc_map[code]}', {day}, '{start}', '{end}', '{room}', '{stype}')"
        for code, day, start, end, room, stype in slots
    ])

    insert_slots_sql = f"""
    insert into classmate.routine_slots (semester_course_id, day_of_week, start_time, end_time, room, type)
    values
    {values_str};
    """
    print("Inserting new routine slots...")
    query(PRODUCTION, insert_slots_sql)
    print("Routine update completed successfully!")

if __name__ == "__main__":
    run_migration()
