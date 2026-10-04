"""Deploy only the two reviewed AI/social migrations, retaining older APIs."""
import sys,pathlib,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING,PRODUCTION
environment=sys.argv[1]
assert environment in ('staging','production')
ref=STAGING if environment=='staging' else PRODUCTION
for version,name in [('202610040005','social_comments'),('202610040006','classmate_ai'),('202610040007','ai_history_permissions'),('202610040008','ai_conversations'),('202610040009','ai_context_courses')]:
 if query(ref,f"select version from supabase_migrations.schema_migrations where version='{version}'"):
  print(name+' already registered in '+environment);continue
 exists=name in ('social_comments','classmate_ai') and query(ref,"select to_regclass('classmate."+('comment_likes' if name=='social_comments' else 'ai_requests')+"') is not null as exists")[0]['exists']
 registration=f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);"
 sql=(root/f'supabase/migrations/{version}_{name}.sql').read_text(encoding='utf-8')
 if exists:
  if environment!='staging':raise RuntimeError('Unregistered production tables require investigation.')
  # Staging was applied and tested during development; preserve its existing rows.
  sql=registration
 else:sql=sql.replace('commit;',registration+'\ncommit;')
 try:query(ref,sql)
 except urllib.error.HTTPError as e:print(e.read().decode());raise
 print(name+' verified in '+environment)
