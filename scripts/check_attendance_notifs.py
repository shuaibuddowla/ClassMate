import sys, pathlib
root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

print("=== CHECKING NOTIFICATIONS ON STAGING & PRODUCTION ===")
for ref, name in [(STAGING, "STAGING"), (PRODUCTION, "PRODUCTION")]:
    rows = query(ref, """
        select e.id, e.kind, e.jobs_prepared, e.delivered_at, e.last_error, count(j.token_hash) as jobs_count
        from classmate.notification_outbox e
        left join classmate.notification_jobs j on j.event_id = e.id
        where e.kind = 'attendance_open'
        group by e.id, e.kind, e.jobs_prepared, e.delivered_at, e.last_error
        order by e.id desc limit 5;
    """)
    print(f"\n[{name}] attendance_open outbox rows ({len(rows)}):")
    for r in rows:
        print(" ", r)
        recipients = query(ref, f"select * from classmate.notification_recipients('{r['id']}');")
        acad_recipients = query(ref, f"select * from classmate.academic_notification_recipients('{r['id']}');")
        print(f"    Recipients returned for event {r['id']}: {len(recipients)} (academic: {len(acad_recipients)})")
