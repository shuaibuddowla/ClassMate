import pathlib, sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION

migration_file = root / 'supabase/migrations/202610220002_grant_rfid_scan_anon.sql'
sql = migration_file.read_text(encoding='utf-8')

for ref, name in [(PRODUCTION, 'production'), (STAGING, 'staging')]:
    print(f"\nApplying rfid anon permissions to {name} ({ref})...")
    try:
        res = query(ref, sql)
        print(f"Applied successfully: {res}")
        reload_res = query(ref, "notify pgrst, 'reload schema';")
        print(f"Schema reload notified: {reload_res}")
    except Exception as e:
        if hasattr(e, 'read'):
            print(f"Error body: {e.read().decode()}")
        print(f"Failed on {name}: {e}")
