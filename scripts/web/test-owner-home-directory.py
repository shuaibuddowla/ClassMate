"""Run rollback-only owner roster assertions without printing profile data."""
import pathlib, sys
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION
import urllib.error
if len(sys.argv) != 2 or sys.argv[1] not in ('staging', 'production'):
    raise SystemExit('Choose staging or production')
ref = STAGING if sys.argv[1] == 'staging' else PRODUCTION
try:
    query(ref, (root / 'supabase/tests/classmate_owner_home_directory.sql').read_text(encoding='utf-8'))
except urllib.error.HTTPError as error:
    print(error.read().decode())
    print(query(ref, "select (select count(*) from classmate.profiles p join classmate.app_owners o on o.email=p.email where o.active and p.role='admin' and p.verification_status='active') as owners,(select count(*) from classmate.batches where is_active) as batches"))
    raise SystemExit(1)
print('Owner home-batch and anonymous access checks passed: ' + sys.argv[1])
