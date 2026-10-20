import sys, pathlib
root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING

print("=== TESTING ATTENDANCE NOTIFICATION RPC ON STAGING ===")
# 1. Fetch current open attendance session
sess = query(STAGING, "select id, routine_slot_id, status from classmate.attendance_sessions where status = 'open' order by started_at desc limit 1;")
if not sess:
    print("No open attendance session found. Let's find any session...")
    sess = query(STAGING, "select id, routine_slot_id, status from classmate.attendance_sessions order by started_at desc limit 1;")
    if sess:
        print(f"Re-opening session {sess[0]['id']} for testing...")
        query(STAGING, f"update classmate.attendance_sessions set status = 'open' where id = '{sess[0]['id']}';")

session_id = sess[0]['id']
print(f"Testing session ID: {session_id}")

# 2. Call notify_attendance_session
res = query(STAGING, f"select classmate.notify_attendance_session('{session_id}') as res;")
print("notify_attendance_session result:", res[0]['res'])

# 3. Check outbox
outbox = query(STAGING, f"""
    select id, kind, record_id, jobs_prepared, delivered_at, attempts, last_error
    from classmate.notification_outbox
    where kind = 'attendance_open' and record_id = '{session_id}';
""")
print("Outbox row:", outbox)

# 4. Prepare jobs (simulating dispatcher worker)
prep = query(STAGING, "select classmate.prepare_notification_jobs(5);")
print("prepare_notification_jobs returned:", prep)

# 5. Check notification_jobs
jobs = query(STAGING, f"""
    select j.event_id, j.profile_id, j.state, j.token_hash
    from classmate.notification_jobs j
    where j.event_id = '{outbox[0]['id']}';
""")
print(f"Notification jobs generated ({len(jobs)}):")
for j in jobs:
    print(" ", j)

# 6. Check context generation for one job
if jobs:
    first_job = jobs[0]
    # Claim it first so it's leased
    claimed = query(STAGING, "select * from classmate.claim_notification_jobs(1);")
    print(f"Claimed job: {claimed}")
    if claimed:
        lease_id = claimed[0]['lease_id']
        token_hash = claimed[0]['token_hash']
        ctx = query(STAGING, f"select classmate.notification_job_context('{outbox[0]['id']}', '{token_hash}', '{lease_id}') as ctx;")
        print("Notification job context:", ctx[0]['ctx'])
