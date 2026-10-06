"""Apply only the owner home-batch directory migration to an explicit environment."""
import pathlib, sys
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION
if len(sys.argv) != 2 or sys.argv[1] not in ('staging', 'production'):
    raise SystemExit('Choose staging or production')
ref = STAGING if sys.argv[1] == 'staging' else PRODUCTION
version = '202610060006'
if not query(ref, f"select version from supabase_migrations.schema_migrations where version='{version}'"):
    sql = (root / 'supabase/migrations/202610060006_owner_home_batch_directory.sql').read_text(encoding='utf-8')
    sql = sql.replace('commit;', f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','owner_home_batch_directory',array[]::text[]);\ncommit;")
    query(ref, sql)
print('Owner roster scope migration applied: ' + sys.argv[1])
