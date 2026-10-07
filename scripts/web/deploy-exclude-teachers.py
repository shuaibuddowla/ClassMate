"""Apply exclude teachers from batch friends migration to staging or production."""
import pathlib, sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION

if len(sys.argv) != 2 or sys.argv[1] not in ('staging', 'production', 'both'):
    raise SystemExit('Usage: python deploy-exclude-teachers.py <staging|production|both>')

targets = [STAGING, PRODUCTION] if sys.argv[1] == 'both' else [STAGING if sys.argv[1] == 'staging' else PRODUCTION]

migrations = [
    ('202610070004', 'exclude_teachers_from_batch_friends'),
]

for ref in targets:
    env_name = 'production' if ref == PRODUCTION else 'staging'
    print(f"\n=== Deploying migrations to {env_name} ({ref}) ===")
    for version, name in migrations:
        existing = query(ref, f"select version from supabase_migrations.schema_migrations where version='{version}'")
        if not existing:
            migration_file = root / f'supabase/migrations/{version}_{name}.sql'
            sql = migration_file.read_text(encoding='utf-8')
            # Inject schema migration record before commit
            sql = sql.replace('commit;', f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;")
            try:
                res = query(ref, sql)
                print(f'Applied {version}_{name}: {res}')
            except Exception as e:
                if hasattr(e, 'read'):
                    print(f'Error body: {e.read().decode()}')
                raise
        else:
            print(f'Migration {version}_{name} already present on {env_name}')

    # Explicitly ensure PostgREST reloads its schema cache
    reload_res = query(ref, "notify pgrst, 'reload schema';")
    print(f'Schema reload notified: {reload_res}')
