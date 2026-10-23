import sys, pathlib
sys.path.insert(0, 'scripts/capacity')
from platform_access import query, PRODUCTION, STAGING

migration_path = pathlib.Path('supabase/migrations/202610230007_attendance_service_role_and_matrix_grants.sql')
sql = migration_path.read_text(encoding='utf-8')

for ref, name in [(PRODUCTION, 'production'), (STAGING, 'staging')]:
    print(f"\nApplying attendance permissions migration to {name} ({ref})...")
    try:
        res = query(ref, sql)
        print(f"Applied successfully: {res}")
        reload_res = query(ref, "notify pgrst, 'reload schema';")
        print(f"Schema reload notified: {reload_res}")
    except Exception as e:
        print(f"Failed on {name}: {e}")
