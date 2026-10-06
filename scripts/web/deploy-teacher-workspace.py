"""Apply the reviewed teacher workspace migration. No fixture data or alerts."""
import sys,pathlib,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING,PRODUCTION
assert sys.argv[1] in ('staging','production')
ref=STAGING if sys.argv[1]=='staging' else PRODUCTION
version='202610060002';name='teacher_gmail_workspace'
if not query(ref,f"select version from supabase_migrations.schema_migrations where version='{version}'"):
 sql=(root/f'supabase/migrations/{version}_{name}.sql').read_text(encoding='utf-8')
 sql=sql.replace('commit;',f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;")
 try:query(ref,sql)
 except urllib.error.HTTPError as e:print(e.read().decode());raise
print('Teacher workspace migration verified in '+sys.argv[1])
