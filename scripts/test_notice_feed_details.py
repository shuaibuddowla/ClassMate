import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

policies = query(PRODUCTION, """
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual
FROM pg_policies
WHERE tablename = 'notice_attachments';
""")
print("Policies on notice_attachments:")
for p in policies:
    print(p)

grants = query(PRODUCTION, """
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_name = 'notice_attachments';
""")
print("\nGrants on notice_attachments:")
for g in grants:
    print(g)
