"""Read-only checks after deployment. No production fixtures or messages."""
import sys,json,pathlib,urllib.request,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,PRODUCTION
checks=[]
result=query(PRODUCTION,"""select
 to_regclass('classmate.batch_bus_schedules') is null as shared_buses_only,
 to_regclass('classmate.comment_likes') is not null as comment_likes,
 to_regclass('classmate.ai_requests') is not null as ai_requests,
 to_regclass('classmate.ai_conversations') is not null as private_conversations,
 not has_table_privilege('authenticated','classmate.ai_messages','INSERT') as trusted_assistant_messages,
 not has_function_privilege('authenticated','classmate.finish_ai_request(uuid,uuid,jsonb,jsonb)','EXECUTE') as private_plans,
 not has_table_privilege('authenticated','classmate.ai_rate_limits','SELECT') as private_rate_limits,
 has_function_privilege('authenticated','classmate.comment_page(uuid,uuid,timestamptz,uuid)','EXECUTE') as reader_comments,
 (select count(*)=5 from supabase_migrations.schema_migrations where version in ('202610040005','202610040006','202610040007','202610040008','202610040009')) as migrations_registered
""")[0]
assert all(result.values()),result
checks.extend(result)
for origin,method,expected in [('https://classmatebd.vercel.app','OPTIONS',204),('https://untrusted.example','OPTIONS',403),('https://classmatebd.vercel.app','POST',401)]:
 req=urllib.request.Request(f'https://{PRODUCTION}.supabase.co/functions/v1/classmate-ai',method=method,data=b'{}' if method=='POST' else None,headers={'Origin':origin,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=30) as r:status=r.status
 except urllib.error.HTTPError as e:status=e.code
 assert status==expected,(origin,method,status)
 checks.append(f'{method} {origin}: {status}')
with urllib.request.urlopen('https://classmatebd.vercel.app',timeout=30) as r:assert r.status==200
checks.append('Production web HTTP 200')
report={'environment':'production','checks':checks,'deployment':'dpl_FAPWM4cYNwTBiXT8fVWQbTzBo2NG'}
(root/'build/classmate-ai-production-checks.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
