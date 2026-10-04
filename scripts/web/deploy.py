"""Explicit environment deployment. Never prints credentials or changes billing."""
import argparse,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'capacity'))
from platform_access import management,query,STAGING,PRODUCTION
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('action',choices=['migration','env','auth']);parser.add_argument('environment',choices=['staging','production']);parser.add_argument('--origin');args=parser.parse_args()
ref=STAGING if args.environment=='staging' else PRODUCTION
if args.action=='migration':
 version='202610030005'
 old=query(ref,f"select version from supabase_migrations.schema_migrations where version='{version}'")
 if not old:
  sql=(ROOT/'supabase/migrations/202610030005_web_clients.sql').read_text()
  sql=sql.replace('commit;',f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','web_clients',array[]::text[]);\ncommit;")
  query(ref,sql)
 for version,name in [('202610040001','browser_push_test'),('202610040002','cr_course_posting')]:
  if not query(ref,f"select version from supabase_migrations.schema_migrations where version='{version}'"):
   sql=(ROOT/f'supabase/migrations/{version}_{name}.sql').read_text().replace('commit;',f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);commit;")
   query(ref,sql)
 print('Web compatibility migrations verified in '+args.environment)
elif args.action=='env':
 cli=str(Path.home()/'AppData/Roaming/npm/node_modules/vercel/dist/index.js')
 for line in (ROOT/'web/.env.local').read_text(encoding='utf-8-sig').splitlines():
  if not line.startswith('NEXT_PUBLIC_'):continue
  name,value=line.split('=',1)
  p=subprocess.run(['node',cli,'env','add',name,'production','--force','--yes','--no-sensitive'],input=value,text=True,cwd=ROOT/'web',capture_output=True)
  if p.returncode:raise RuntimeError('Vercel environment update failed for '+name+': '+p.stderr)
  print('Configured '+name)
elif args.action=='auth':
 if not args.origin or not args.origin.startswith('https://'):raise RuntimeError('Explicit HTTPS origin required')
 current=management('projects/'+ref+'/config/auth')
 existing=[v for v in (current.get('uri_allow_list') or '').split(',') if v]
 for url in [args.origin+'/auth/callback']+(['http://localhost:3000/auth/callback'] if args.environment=='staging' else []):
  if url not in existing:existing.append(url)
 management('projects/'+ref+'/config/auth','PATCH',{'uri_allow_list':','.join(existing)})
 print('Added web OAuth callback; existing Android callbacks preserved.')
