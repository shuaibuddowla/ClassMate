"""Apply the owner-supplied October 6 routine, preserving matching slot IDs."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

ROOT = Path(__file__).resolve().parents[1]
BATCH = 'de0b3379-54a0-4275-a526-5970295c787e'
SEMESTER = '56056dc8-5c95-40d5-95b5-162a6ff4cd49'
COURSES = {
    'CSE2101': ('Object Oriented Programming', 'Dr. Md. Sazzad Hossain'),
    'CSE2102': ('Object Oriented Programming Lab', 'Dr. Md. Sazzad Hossain'),
    'CSE2103': ('Data Structures & Algorithms', 'Dr. Mehedi Hasan Talukder'),
    'CSE2104': ('Data Structures & Algorithms Lab', 'Dr. Mehedi Hasan Talukder'),
    'CSE2105': ('Computer Organization & Architecture', 'Md. Hadifur Rahman'),
    'CSE2107': ('Matrix & Linear Algebra', 'Dr. Mohammad Mokaddes Ali'),
}
PERIODS = [
    (6, '10:00', '11:50', 'CSE2104', 'Lab-02'),
    (6, '12:00', '12:50', 'CSE2107', '338'),
    (0, '10:00', '11:50', 'CSE2105', '338'),
    (0, '12:00', '12:50', 'CSE2103', '341'),
    (0, '13:45', '15:10', 'CSE2104', 'Lab-02'),
    (0, '15:15', '15:55', 'CSE2101', '338'),
    (1, '10:00', '10:50', 'CSE2107', '338'),
    (1, '12:00', '12:50', 'CSE2103', '341'),
    (1, '13:45', '14:25', 'CSE2101', '338'),
    (1, '14:30', '15:55', 'CSE2102', 'Lab-01'),
    (2, '10:00', '10:50', 'CSE2101', '338'),
    (2, '11:00', '11:50', 'CSE2105', '338'),
    (2, '12:00', '12:50', 'CSE2103', '338'),
    (2, '13:45', '15:10', 'CSE2102', 'Lab-01'),
    (3, '10:00', '10:50', 'CSE2107', '341'),
]

def literal(value):
    return "'" + str(value).replace("'", "''") + "'"

def main():
    tables = ['departments', 'batches', 'semesters', 'courses', 'semester_courses', 'teacher_directory', 'routine_slots']
    conditions = {
        'departments': f"id=(select department_id from classmate.batches where id='{BATCH}')",
        'batches': f"id='{BATCH}'",
        'semesters': f"id='{SEMESTER}'",
        'courses': f"id in(select course_id from classmate.semester_courses where semester_id='{SEMESTER}')",
        'semester_courses': f"semester_id='{SEMESTER}'",
        'teacher_directory': f"id in(select teacher_record_id from classmate.semester_courses where semester_id='{SEMESTER}')",
        'routine_slots': f"semester_course_id in(select id from classmate.semester_courses where semester_id='{SEMESTER}')",
    }
    snapshot = {table: query(PRODUCTION, f"select * from classmate.{table} where {conditions[table]}") for table in tables}
    assert len(snapshot['semesters']) == 1 and snapshot['semesters'][0]['status'] == 'active'
    assert snapshot['batches'][0]['batch_number'] == 22 and snapshot['semesters'][0]['semester_number'] == 3
    assert snapshot['departments'][0]['code'] == 'cse'
    catalog = {r['id']: r for r in snapshot['courses']}
    offerings = {catalog[r['course_id']]['course_code']: r for r in snapshot['semester_courses']}
    assert all(code in offerings for code in COURSES)
    assert all(offerings[code]['teacher_record_id'] for code in COURSES)
    expected = [{'semester_course_id': offerings[code]['id'], 'day_of_week': day,
                 'start_time': start + ':00', 'end_time': end + ':00', 'room': room,
                 'type': 'lab' if code in ('CSE2102', 'CSE2104') else 'class'}
                for day, start, end, code, room in PERIODS]
    backup = ROOT / 'deliverables' / 'cse22-routine-before-20261006.json'
    backup.parent.mkdir(exist_ok=True)
    if not backup.exists():
        backup.write_text(json.dumps(snapshot, indent=2), encoding='utf-8')
    sql = 'begin;\n'
    sql += f"create temporary table desired as select * from jsonb_to_recordset({literal(json.dumps(expected))}::jsonb) as r(semester_course_id uuid,day_of_week smallint,start_time time,end_time time,room text,type text);\n"
    sql += f"""do $$ begin
      if not exists(select 1 from classmate.semesters s join classmate.batches b on b.id=s.batch_id
        join classmate.departments d on d.id=b.department_id where s.id='{SEMESTER}' and b.id='{BATCH}'
        and b.batch_number=22 and d.code='cse' and s.semester_number=3 and s.status='active') then
        raise exception 'Target semester changed'; end if;
      if exists(select 1 from desired a join desired b on a.day_of_week=b.day_of_week
        and a.start_time<b.start_time and a.end_time>b.start_time) then raise exception 'Overlapping periods'; end if;
    end $$;
    update classmate.routine_slots r set end_time=d.end_time,room=d.room,type=d.type
      from desired d where r.semester_course_id=d.semester_course_id and r.day_of_week=d.day_of_week and r.start_time=d.start_time;
    delete from classmate.routine_slots r where r.semester_course_id in
      (select id from classmate.semester_courses where semester_id='{SEMESTER}')
      and not exists(select 1 from desired d where r.semester_course_id=d.semester_course_id
        and r.day_of_week=d.day_of_week and r.start_time=d.start_time);
    insert into classmate.routine_slots(semester_course_id,day_of_week,start_time,end_time,room,type)
      select d.* from desired d where not exists(select 1 from classmate.routine_slots r where
        r.semester_course_id=d.semester_course_id and r.day_of_week=d.day_of_week and r.start_time=d.start_time);
    """
    for code, (title, teacher) in COURSES.items():
        sc = offerings[code]
        sql += f"update classmate.semester_courses set title_override={literal(title)} where id='{sc['id']}';\n"
        sql += f"update classmate.teacher_directory set full_name={literal(teacher)},updated_at=now() where id='{sc['teacher_record_id']}';\n"
    verify_sql = f"select r.semester_course_id,r.day_of_week,r.start_time,r.end_time,r.room,r.type from classmate.routine_slots r join classmate.semester_courses sc on sc.id=r.semester_course_id where sc.semester_id='{SEMESTER}'"
    sql += f"""do $$ begin
      if exists(({verify_sql} except select * from desired) union all
        (select * from desired except {verify_sql})) or (select count(*) from classmate.routine_slots
        where semester_course_id in(select id from classmate.semester_courses where semester_id='{SEMESTER}'))<>15
      then raise exception 'Routine verification failed'; end if;
    end $$;
    """
    # Exercise the exact mutations against temporary copies on staging; leave staging unchanged.
    setup = 'begin;\n'
    for table in tables:
        setup += f"create temporary table {table} (like classmate.{table} including defaults);\n"
        setup += f"insert into {table} select * from jsonb_populate_recordset(null::pg_temp.{table},{literal(json.dumps(snapshot[table]))}::jsonb);\n"
    query(STAGING, setup + sql.removeprefix('begin;\n').replace('classmate.', 'pg_temp.') + 'rollback;')
    print('Staging validation passed; no staging data retained.')
    query(PRODUCTION, sql + 'rollback;')
    print('Production transaction dry run passed.')
    if '--apply' not in sys.argv:
        return
    query(PRODUCTION, sql + 'commit;')
    actual = query(PRODUCTION, verify_sql)
    normalize = lambda rows: sorted(json.dumps(r, sort_keys=True) for r in rows)
    assert normalize(actual) == normalize(expected)
    teachers = query(PRODUCTION, f"select c.course_code,sc.title_override,t.full_name from classmate.semester_courses sc join classmate.courses c on c.id=sc.course_id join classmate.teacher_directory t on t.id=sc.teacher_record_id where sc.semester_id='{SEMESTER}'")
    for row in teachers:
        if row['course_code'] in COURSES:
            assert (row['title_override'], row['full_name']) == COURSES[row['course_code']]
    print('Production verified: all 15 periods and six course/teacher mappings match the supplied routine.')
    print('Backup:', backup)

if __name__ == '__main__':
    main()
