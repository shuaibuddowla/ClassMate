import sys
from pathlib import Path
sys.path.insert(0, str(Path("scripts/capacity")))
from platform_access import query, PRODUCTION

res = query(PRODUCTION, """
SELECT policyname, roles, cmd, qual, with_check 
FROM pg_policies 
WHERE tablename = 'objects';
""")
for p in res:
    print(p['policyname'], p['cmd'], p['roles'])
