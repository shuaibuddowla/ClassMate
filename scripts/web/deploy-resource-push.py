import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts' / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

version = '202610230002'
name = 'resource_push_notifications'
sql_file = root / f'supabase/migrations/{version}_{name}.sql'
sql = sql_file.read_text(encoding='utf-8')
sql_to_run = sql.replace('commit;', f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;")

for env_name, ref in [('staging', STAGING), ('production', PRODUCTION)]:
    print(f"\n=== Deploying {version}_{name} to {env_name} ({ref}) ===")
    existing = query(ref, f"select version from supabase_migrations.schema_migrations where version='{version}'")
    if existing:
        print(f"Migration {version} already registered in {env_name}. Re-applying SQL...")
        query(ref, sql)
    else:
        print(f"Applying migration {version}_{name} to {env_name}...")
        query(ref, sql_to_run)
    print(f"Verified {version}_{name} on {env_name}!")
