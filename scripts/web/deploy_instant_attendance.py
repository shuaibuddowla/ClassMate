import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION

version = '202610130001'
name = 'instant_attendance_timetable_updates'
sql_file = root / f'supabase/migrations/{version}_{name}.sql'
sql = sql_file.read_text(encoding='utf-8')
sql_to_run = sql.replace('commit;', f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;")

for ref, label in [(STAGING, 'staging'), (PRODUCTION, 'production')]:
    print(f"Deploying to {label} ({ref})...")
    existing = query(ref, f"select version from supabase_migrations.schema_migrations where version='{version}'")
    if not existing:
        res = query(ref, sql_to_run)
        print(f"Applied on {label}: {res}")
    else:
        print(f"Already present on {label}")
    query(ref, "notify pgrst, 'reload schema';")
    print(f"Schema reloaded on {label}")
