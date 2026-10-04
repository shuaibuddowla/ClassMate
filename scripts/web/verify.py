"""Small authenticated web fixtures, restricted to staging; no mass push."""
import argparse,json,sys,urllib.request,urllib.error,base64,hmac,hashlib,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'capacity'))
from platform_access import STAGING,query,api_keys,management
from fixtures import sign
ROOT=Path(__file__).resolve().parents[2];PREFIX='ae042026-'
def uid(kind,index=1):return f'{PREFIX}{kind:04d}-4000-8000-{index:012d}'
def cleanup():
 files=query(STAGING,"select id from classmate.file_metadata where batch_id::text like 'ae042026-%'")
 if files:raise RuntimeError('Delete tagged uploaded R2 files through the Edge Function before fixture cleanup.')
 query(STAGING,f"""begin;
 delete from classmate.notification_outbox where batch_id::text like '{PREFIX}%';
 delete from classmate.notices where batch_id::text like '{PREFIX}%';
 delete from classmate.file_metadata where batch_id::text like '{PREFIX}%';
 delete from classmate.routine_slots where semester_course_id in(select id from classmate.semester_courses where semester_id in(select id from classmate.semesters where batch_id::text like '{PREFIX}%'));
 delete from classmate.class_changes where batch_id::text like '{PREFIX}%';
 delete from classmate.teacher_course_assignments where semester_course_id::text like '{PREFIX}%';
 delete from classmate.semester_courses where semester_id in(select id from classmate.semesters where batch_id::text like '{PREFIX}%');
 delete from classmate.teacher_directory where department_id::text like '{PREFIX}%';
 delete from classmate.courses where department_id::text like '{PREFIX}%';
 delete from classmate.teacher_allowlist where department_id::text like '{PREFIX}%';
 delete from classmate.profiles where id::text like '{PREFIX}%';
 delete from auth.users where id::text like '{PREFIX}%';
 delete from classmate.semesters where batch_id::text like '{PREFIX}%';
 delete from classmate.batches where id::text like '{PREFIX}%';
 delete from classmate.departments where id::text like '{PREFIX}%';
 delete from classmate.bus_schedules where id::text like '{PREFIX}%';
 delete from classmate.audit_log where target_id::text like '{PREFIX}%' or actor_id::text like '{PREFIX}%';
 commit;""")
 print('Removed only tagged web staging fixtures.')
def seed():
 cleanup();owner=query(STAGING,"select p.id from classmate.profiles p join classmate.app_owners o on o.email=p.email and o.active limit 1")[0]['id']
 dept=uid(1);a,b=uid(2),uid(2,2);oa,ob=uid(5),uid(5,2)
 sql=["begin;",f"insert into classmate.departments(id,name,code,email_prefix,session_offset,is_active) values('{dept}','Web test department','web_20261004','wb',3,true);"]
 for i,batch in enumerate([a,b],1):
  sql.extend([f"insert into classmate.batches(id,department_id,batch_number,academic_session) values('{batch}','{dept}',{22+i},{25+i});",f"update classmate.semesters set id='{uid(3,i)}',status='active' where batch_id='{batch}' and semester_number=1;",f"insert into classmate.courses(id,department_id,course_code,course_title,course_type) values('{uid(4,i)}','{dept}','WEB{i}','Web test course {i}','theory');",f"insert into classmate.semester_courses(id,semester_id,course_id) values('{uid(5,i)}','{uid(3,i)}','{uid(4,i)}');",f"insert into classmate.routine_slots(id,semester_course_id,day_of_week,start_time,end_time,room) values('{uid(8,i)}','{uid(5,i)}',0,'09:00','09:45','101');"])
 users=[]
 for i,role in enumerate(['student','student','student','teacher'],1):
  pid=uid(6,i);email=f'wb{26 if i!=2 else 27}{i:03d}@mbstu.ac.bd';batch=b if i==2 else a
  student_id='null' if role=='teacher' else repr(f'WB{26 if i!=2 else 27}{i:03d}')
  session='null' if role=='teacher' else str(26 if i!=2 else 27)
  sql.extend([f"insert into auth.users(id,email,email_confirmed_at,aud,role,raw_user_meta_data) values('{pid}','{email}',now(),'authenticated','authenticated','{{\"full_name\":\"Web Test {role.title()} {i}\"}}'::jsonb);",f"insert into auth.identities(id,provider_id,user_id,identity_data,provider) values('{pid}','{pid}','{pid}','{{\"sub\":\"{pid}\",\"email\":\"{email}\"}}'::jsonb,'google');",f"insert into classmate.profiles(id,email,full_name,student_id,role,department_id,batch_id,academic_session,verification_status,is_cr,cr_batch_id,profile_completed_at) values('{pid}','{email}','Web Test {role.title()} {i}',{student_id},'{role}','{dept}',{'null' if role=='teacher' else repr(batch)},{session},'active',{'true' if i==3 else 'false'},{repr(a) if i==3 else 'null'},now());"])
  users.append({'id':pid,'email':email,'role':'cr' if i==3 else role,'batch':batch,'offering':ob if i==2 else oa})
 sql.extend([f"insert into classmate.teacher_allowlist(email,department_id,created_by) values('{users[-1]['email']}','{dept}','{owner}');",f"insert into classmate.teacher_course_assignments(teacher_id,semester_course_id,assigned_by) values('{users[-1]['id']}','{oa}','{owner}');"])
 for i in range(1,24):
  sql.append(f"insert into classmate.notices(id,department_id,batch_id,author_id,title,body,silent,published_at) values('{uid(7,i)}','{dept}','{a}','{users[2]['id']}','Web notice {i}','A synthetic notice for the web test. Visit https://www.mbstu.ac.bd for university information.',true,now()-interval '{i} minutes');")
 sql.append(f"insert into classmate.notices(id,department_id,batch_id,author_id,title,body,silent) values('{uid(7,100)}','{dept}','{b}','{users[1]['id']}','Other batch private notice','Must not leak across batches',true);")
 for i,kind in enumerate(['office_open','closed'],1):
  sql.append(f"insert into classmate.bus_schedules(id,route_name,departure_time,city_departure_time,schedule_kind,origin,destination,weekdays,active,created_by) values('{uid(9,i)}','Synthetic web bus','05:17','05:47','{kind}','Campus','City',array[0,1,2,3,4,5,6]::smallint[],true,'{owner}');")
 sql.append('commit;');query(STAGING,'\n'.join(sql))
 secret=management('projects/'+STAGING+'/postgrest')['jwt_secret'];anon=api_keys(STAGING)['anon']
 query(STAGING,"update auth.users set instance_id='00000000-0000-0000-0000-000000000000',encrypted_password='',confirmation_token='',recovery_token='',email_change_token_new='',email_change_token_current='',email_change='',phone_change='',phone_change_token='',reauthentication_token='',email_change_confirm_status=0,is_sso_user=false,is_anonymous=false,is_super_admin=false,created_at=now(),updated_at=now(),raw_app_meta_data='{\"provider\":\"google\",\"providers\":[\"google\"]}'::jsonb where id::text like 'ae042026-%'; update auth.identities set created_at=now(),updated_at=now(),last_sign_in_at=now() where user_id::text like 'ae042026-%';")
 def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).decode().rstrip('=')
 for u in users:
  sid=u['id'].replace('0006','0010')
  query(STAGING,f"insert into auth.sessions(id,user_id,aal,created_at,updated_at,not_after) values('{sid}','{u['id']}','aal1',now(),now(),now()+interval '2 hours')")
  payload={'aud':'authenticated','role':'authenticated','sub':u['id'],'session_id':sid,'aal':'aal1','email':u['email'],'iss':f'https://{STAGING}.supabase.co/auth/v1','exp':int(time.time())+7200,'iat':int(time.time()),'app_metadata':{'provider':'google','providers':['google']}}
  token=enc({'alg':'HS256','typ':'JWT'})+'.'+enc(payload)
  u['token']=token+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),token.encode(),hashlib.sha256).digest()).decode().rstrip('=')
 # Owner is used only for read-only health and rollback authorization checks.
 owner_user={'id':owner,'token':sign(secret,owner),'role':'owner'}
 data={'ref':STAGING,'anon':anon,'users':users,'owner':owner_user,'batch':a,'notice':uid(7,1),'other_notice':uid(7,100)}
 (ROOT/'build/web-fixtures-private.json').write_text(json.dumps(data))
 print('Created four isolated staging accounts, two batches and 24 silent notices.')
def request(data,user,path,body=None):
 req=urllib.request.Request(f'https://{STAGING}.supabase.co/rest/v1/'+path,data=json.dumps(body).encode() if body is not None else None,headers={'apikey':data['anon'],'Authorization':'Bearer '+user['token'],'Accept-Profile':'classmate','Content-Profile':'classmate','Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=30) as response:return response.status,json.loads(response.read() or 'null')
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def verify():
 data=json.loads((ROOT/'build/web-fixtures-private.json').read_text());student,other,cr,teacher=data['users'];checks=[]
 def check(name,condition):
  assert condition,name;checks.append(name)
 status,notices=request(data,student,'notices?select=id&order=published_at.desc,id.desc&limit=10');check('ten-notice page',status==200 and len(notices)==10)
 status,hidden=request(data,student,'notices?id=eq.'+data['other_notice']);check('cross-batch notice denied',status==200 and not hidden)
 for u in [student,other,cr,teacher]:
  status,_=request(data,u,'rpc/system_health',{});check(u['role']+' denied owner diagnostics',status==403)
 status,_=request(data,data['owner'],'rpc/system_health',{});check('owner diagnostics allowed',status==200)
 status,details=request(data,student,'rpc/notice_feed_details',{'target_ids':[data['notice']]});check('bounded feed details',status==200 and 'engagement'in details)
 status,_=request(data,student,'rpc/mark_notices_read',{'target_ids':[data['notice']]});check('read receipt write',200<=status<300)
 status,readers=request(data,student,'rpc/notice_readers_page',{'target_notice':data['notice'],'page_size':50});check('student can see readers',status==200 and len(readers)==1)
 status,_=request(data,student,'rpc/batch_friends',{'target_batch':other['batch']});check('cross-batch Friends denied',status==403)
 status,can=request(data,cr,'rpc/can_manage_batch_catalog',{'target_batch':cr['batch']});check('CR catalog permission',status==200 and can is True)
 status,can=request(data,student,'rpc/can_manage_batch_catalog',{'target_batch':student['batch']});check('student catalog denied',status==200 and can is False)
 status,_=request(data,teacher,'rpc/batch_course_catalog',{'target_batch':other['batch']});check('teacher limited to assigned batch',status==403)
 status,_=request(data,cr,'rpc/register_web_device_token',{'target_token':'web-synthetic-token-'+cr['id']});check('web registration authenticated',200<=status<300)
 query(STAGING,"delete from classmate.device_tokens where fcm_token like 'web-synthetic-token-%'")
 for origin,expected in [('http://localhost:3000',204),('https://untrusted.example',403)]:
  req=urllib.request.Request(f'https://{STAGING}.supabase.co/functions/v1/signed-classmate-resource',method='OPTIONS',headers={'Origin':origin,'Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'authorization,apikey,content-type'})
  try:
   with urllib.request.urlopen(req) as r:status=r.status
  except urllib.error.HTTPError as e:status=e.code
  check('CORS '+origin,status==expected)
 report={'environment':'staging','passed':checks,'count':len(checks)};(ROOT/'build/web-api-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['seed','verify','cleanup']);a=p.parse_args();globals()[a.mode]();
 if a.mode=='cleanup':(ROOT/'build/web-fixtures-private.json').unlink(missing_ok=True)
