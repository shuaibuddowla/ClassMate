import sys,pathlib,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
try:query(STAGING,(root/'supabase/tests/classmate_teacher_workspace.sql').read_text(encoding='utf-8-sig'))
except urllib.error.HTTPError as e:print(e.read().decode());raise
print('Teacher Gmail, assignment scope, student compatibility and revocation checks passed; fixtures rolled back.')
