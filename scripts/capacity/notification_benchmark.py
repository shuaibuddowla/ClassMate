"""Staging-only mock FCM benchmarks. Never sends synthetic tokens to Google."""
import datetime,json,re,subprocess,time,urllib.request,threading,queue
from pathlib import Path
from platform_access import STAGING,query
from fixtures import uid
CLI=['node','node_modules/supabase/dist/supabase.js']
def mock(enabled):
 result=subprocess.run(CLI+['secrets','set','CLASSMATE_FCM_MOCK='+str(enabled).lower(),'--project-ref',STAGING],capture_output=True,text=True)
 if result.returncode:raise RuntimeError('Could not set staging mock mode')
def _dispatch_http():
 text=Path('supabase/.classmate-staging.local.env').read_text(encoding='utf-8-sig')
 secret=re.search(r'^CLASSMATE_DISPATCH_SECRET=(.+)$',text,re.M).group(1).strip().strip('"').strip("'")
 request=urllib.request.Request(f'https://{STAGING}.supabase.co/functions/v1/dispatch-classmate-notifications',data=b'{}',method='POST',headers={'Content-Type':'application/json','x-dispatch-secret':secret})
 with urllib.request.urlopen(request,timeout=90) as response:return json.load(response)
def dispatch():
 responses=queue.Queue()
 def request():
  try:responses.put((True,_dispatch_http()))
  except Exception as error:responses.put((False,error))
 threading.Thread(target=request,daemon=True).start()
 while True:
  resource_guard() # Stop and clean the event even during a long invocation.
  try:ok,value=responses.get(timeout=1)
  except queue.Empty:continue
  if not ok:raise value
  return value

def resource_guard():
 sample=json.loads(Path('build/capacity-resource.json').read_text(encoding='utf-8'))
 age=time.time()-datetime.datetime.fromisoformat(sample['observed_at']).timestamp()
 if age>120:raise RuntimeError('Resource diagnostics became stale; benchmark stopped')
 if sample['maximum_fraction']>=.85:raise RuntimeError('Verified resource usage reached 85%; benchmark stopped')

def run():
 results=[]
 # All seed notices are silent. Dedicated benchmark events reference tagged notices only.
 try:
  mock(True)
  for batch,size in [(1,100),(2,500),(3,1000),(0,5000)]:
   resource_guard()
   record=uid(8,size)
   if batch:
    source=uid(7,batch*100+1)
    query(STAGING,f"insert into classmate.notices(id,department_id,batch_id,author_id,title,body,silent) select '{record}',department_id,batch_id,author_id,'Mock capacity notice {size}','Staging-only mock provider test',false from classmate.notices where id='{source}';")
   else:
    query(STAGING,f"insert into classmate.app_releases(id,version_code,version_name) values('{record}',990000,'0.0.0');insert into classmate.notification_outbox(kind,record_id,routing_label) values('app_update','{record}','staging-mock-capacity');")
   started=time.monotonic();runs=0
   while time.monotonic()-started<(120 if size==500 else 1200):
    resource_guard()
    response=dispatch()
    if not response.get('mock'):raise RuntimeError('Staging dispatcher did not confirm mock mode')
    runs+=1
    stats=query(STAGING,f"select count(*) filter(where j.state='accepted') as accepted,count(*) filter(where j.state='failed') as failed from classmate.notification_jobs j join classmate.notification_outbox e on e.id=j.event_id where e.record_id='{record}' and j.profile_id::text like 'd5202610-%';")[0]
    if stats['accepted']>=size:break
    if stats['failed']:raise RuntimeError('Healthy mock delivery unexpectedly failed')
   result={'devices':size,'accepted':stats['accepted'],'seconds':round(time.monotonic()-started,2),'invocations':runs,'mock_provider':True,'provider_latency_ms':100,'passed':stats['accepted']==size and time.monotonic()-started<=(120 if size==500 else 1200)}
   results.append(result);print(json.dumps(result),flush=True)
   Path('build/capacity-notification-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
   # Delete global mock events before restoring FCM, including any real staged device jobs.
   query(STAGING,f"delete from classmate.notification_outbox where record_id='{record}';delete from classmate.notices where id='{record}';delete from classmate.app_releases where id='{record}';")
 except Exception as error:
  progress=query(STAGING,f"select count(*) filter(where j.state='accepted') accepted,count(*) filter(where j.state in('pending','leased')) pending from classmate.notification_jobs j join classmate.notification_outbox e on e.id=j.event_id where e.record_id='{record}'") if 'record' in locals() else []
  Path('build/capacity-notification-stop.json').write_text(json.dumps({'reason':str(error),'devices':size if 'size' in locals() else None,'progress':progress,'observed_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}),encoding='utf-8')
  raise
 finally:
  # No production record is touched; these IDs are exclusively generated fixtures.
  query(STAGING,"delete from classmate.notification_outbox where record_id::text like 'd5202610-0008-%';delete from classmate.notices where id::text like 'd5202610-0008-%';delete from classmate.app_releases where id::text like 'd5202610-0008-%';")
  mock(False)
if __name__=='__main__':run()
