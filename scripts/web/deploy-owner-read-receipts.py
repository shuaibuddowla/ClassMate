"""Apply the owner read receipts preference migration to staging or production."""
import pathlib, sys
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION

if len(sys.argv) != 2 or sys.argv[1] not in ('staging', 'production'):
    raise SystemExit('Usage: python deploy-owner-read-receipts.py <staging|production>')

target = sys.argv[1]
ref = STAGING if target == 'staging' else PRODUCTION
version = '202610070001'
name = 'owner_read_receipts_preference'

existing = query(ref, f"select version from supabase_migrations.schema_migrations where version='{version}'")
if not existing:
    migration_file = root / f'supabase/migrations/{version}_{name}.sql'
    sql = migration_file.read_text(encoding='utf-8')
    sql = sql.replace('commit;', f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;")
    res = query(ref, sql)
    print(f'Applied migration {version}_{name} to {target}: {res}')
else:
    print(f'Migration {version} already applied on {target}')
