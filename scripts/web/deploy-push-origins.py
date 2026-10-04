"""Origin migration; only aggregate diagnostics are printed, never tokens."""
import sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING,PRODUCTION
environment=sys.argv[1]
assert environment in ('staging','production')
ref=STAGING if environment=='staging' else PRODUCTION
version='202610040010'
if not query(ref,f"select version from supabase_migrations.schema_migrations where version='{version}'"):
 sql=(root/f'supabase/migrations/{version}_web_push_origins.sql').read_text()
 if environment=='staging':
  sql=sql.replace("('https://classmatebd.vercel.app')", "('https://classmate-web-staging.vercel.app'), ('http://localhost:3000')")
 registration=f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','web_push_origins',array[]::text[]);"
 query(ref,sql.replace('commit;',registration+'\ncommit;'))
print(environment,query(ref,"select origin from classmate.web_push_origins order by origin"))
print('Registration counts',query(ref,"select client_platform,count(*) as registrations,count(*) filter(where web_origin is not null) as origin_known from classmate.device_tokens group by client_platform"))
