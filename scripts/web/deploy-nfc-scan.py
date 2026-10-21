import pathlib, sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, STAGING, PRODUCTION

migration_file = root / 'supabase/migrations/202610220003_support_phone_nfc_scan.sql'
sql = migration_file.read_text(encoding='utf-8')

for ref, name in [(PRODUCTION, 'production'), (STAGING, 'staging')]:
    print(f"\nApplying phone NFC migration to {name} ({ref})...")
    res = query(ref, sql)
    print(f"Applied: {res}")
    reload_res = query(ref, "notify pgrst, 'reload schema';")
    print(f"Schema reload notified: {reload_res}")
