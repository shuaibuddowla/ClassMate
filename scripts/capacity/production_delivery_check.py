"""One-device production FCM acceptance check; fixtures and token ownership restored."""
import json,re,time,urllib.request
from pathlib import Path
from platform_access import PRODUCTION,query
PREFIX='f3202610-0000-4000-8000-'
DEPT=PREFIX+'000000000001';BATCH=PREFIX+'000000000002';USER=PREFIX+'000000000003';NOTICE=PREFIX+'000000000004'
cleanup=f"""begin;
update classmate.device_tokens set profile_id=(select id from classmate.profiles where role='admin') where profile_id='{USER}';
delete from classmate.notification_outbox where record_id='{NOTICE}';
delete from classmate.notices where id='{NOTICE}';
delete from classmate.profiles where id='{USER}';
delete from auth.users where id='{USER}';
delete from classmate.semesters where batch_id='{BATCH}';
delete from classmate.batches where id='{BATCH}';
delete from classmate.departments where id='{DEPT}';
commit;"""
def run():
 counts=query(PRODUCTION,"select (select count(*) from classmate.profiles where role='admin') owners,(select count(*) from classmate.device_tokens d join classmate.profiles p on p.id=d.profile_id where p.role='admin') devices")[0]
 if counts['owners']!=1 or counts['devices']!=1:raise RuntimeError('Exactly one owner and registered owner device required for controlled check')
 try:
  query(PRODUCTION,f"""begin;
insert into classmate.departments(id,name,code,is_active) values('{DEPT}','Disposable delivery check','fcmcheck_202610',true);
insert into classmate.batches(id,department_id,batch_number,academic_session) values('{BATCH}','{DEPT}',99,99);
insert into auth.users(id,email,email_confirmed_at,aud,role) values('{USER}','delivery-check@mbstu.ac.bd',now(),'authenticated','authenticated');
insert into classmate.profiles(id,email,full_name,role,department_id,batch_id,verification_status) values('{USER}','delivery-check@mbstu.ac.bd','Disposable delivery check','student','{DEPT}','{BATCH}','active');
update classmate.device_tokens set profile_id='{USER}' where profile_id=(select id from classmate.profiles where role='admin');
insert into classmate.notices(id,department_id,batch_id,author_id,title,body) select '{NOTICE}','{DEPT}','{BATCH}',id,'ClassMate delivery check','Controlled single-device acceptance test' from classmate.profiles where role='admin';
commit;""")
  secret=re.search(r'^CLASSMATE_DISPATCH_SECRET=(.+)$',Path('supabase/.classmate-secrets.local.env').read_text(encoding='utf-8-sig'),re.M).group(1).strip().strip('"').strip("'")
  request=urllib.request.Request(f'https://{PRODUCTION}.supabase.co/functions/v1/dispatch-classmate-notifications',data=b'{}',method='POST',headers={'Content-Type':'application/json','x-dispatch-secret':secret})
  with urllib.request.urlopen(request,timeout=90) as response:result=json.load(response)
  stats=query(PRODUCTION,f"select count(*) filter(where j.state='accepted') accepted,count(*) total from classmate.notification_jobs j join classmate.notification_outbox e on e.id=j.event_id where e.record_id='{NOTICE}'")[0]
  if result.get('mock') or stats['total']!=1 or stats['accepted']!=1:raise RuntimeError('Single-device production acceptance failed')
  report={'accepted':1,'devices':1,'mock':False,'phone_display':'Not verified: disposable recipient identity is intentionally rejected by the signed-in owner client','observed_at':time.time()}
  Path('build/capacity-production-delivery.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
 finally:query(PRODUCTION,cleanup)
if __name__=='__main__':run()
