import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql = Path('supabase/migrations/202611210001_upcoming_assessments.sql').read_text(encoding='utf-8')
print("Deploying migration...")
res = query(PRODUCTION, sql)
print("Migration result:", res)
