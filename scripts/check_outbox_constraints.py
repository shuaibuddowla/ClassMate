import sys, pathlib
root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

for ref, name in [(STAGING, "STAGING"), (PRODUCTION, "PRODUCTION")]:
    constrs = query(ref, """
        select conname, pg_get_constraintdef(c.oid) as def
        from pg_constraint c
        join pg_namespace n on n.oid = c.connamespace
        where n.nspname = 'classmate' and conrelid = 'classmate.notification_outbox'::regclass;
    """)
    print(f"\n[{name}] notification_outbox constraints:")
    for c in constrs:
        print(" ", c['conname'], "->", c['def'])
