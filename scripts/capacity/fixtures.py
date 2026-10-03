"""Tagged synthetic fixture setup. Hard restricted to ClassMate Staging."""
import argparse,base64,hashlib,hmac,json,time,uuid
from pathlib import Path
from platform_access import STAGING,query,management,api_keys

PREFIX='d5202610-'
def uid(kind,index):return f'd5202610-{kind:04d}-4000-8000-{index:012d}'
def cleanup():
 return query(STAGING,"""begin;
delete from classmate.notification_outbox where record_id::text like 'd5202610-%';
delete from classmate.app_releases where id::text like 'd5202610-%';
delete from classmate.notices where id::text like 'd5202610-%';
delete from classmate.routine_slots where semester_course_id::text like 'd5202610-%';
delete from classmate.semester_courses where id::text like 'd5202610-%';
delete from classmate.courses where id::text like 'd5202610-%';
delete from classmate.semesters where batch_id::text like 'd5202610-%';
delete from classmate.profiles where id::text like 'd5202610-%';
delete from auth.users where id::text like 'd5202610-%';
delete from classmate.batches where id::text like 'd5202610-%';
delete from classmate.departments where id::text like 'd5202610-%';
commit;""")
def sign(secret,profile):
 def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
 token=enc({'alg':'HS256','typ':'JWT'})+'.'+enc({'aud':'authenticated','role':'authenticated','sub':profile,'iss':f'https://{STAGING}.supabase.co/auth/v1','exp':int(time.time())+7200,'iat':int(time.time())})
 return token+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),token.encode(),hashlib.sha256).digest()).decode().rstrip('=')
def seed():
 cleanup()
 dept=uid(1,1)
 sql=["begin;",f"insert into classmate.departments(id,name,code,is_active) values('{dept}','Capacity test fixtures','capacity_202610',true);"]
 users=[];number=0
 for batch,size in enumerate([100,500,1000,3400],1):
  bid,sid,cid,oid=(uid(k,batch) for k in [2,3,4,5])
  sql += [f"insert into classmate.batches(id,department_id,batch_number,academic_session) values('{bid}','{dept}',{batch},26);",f"update classmate.semesters set id='{sid}',status='active' where batch_id='{bid}' and semester_number=1;",f"insert into classmate.courses(id,department_id,course_code,course_title,course_type) values('{cid}','{dept}','LT{batch}','Capacity course {batch}','theory');",f"insert into classmate.semester_courses(id,semester_id,course_id) values('{oid}','{sid}','{cid}');",f"insert into classmate.routine_slots(semester_course_id,day_of_week,start_time,end_time,room) values('{oid}',1,'09:00','09:45','101');"]
  first=number+1
  last=number+size
  expr="('d5202610-0006-4000-8000-'||lpad(g::text,12,'0'))::uuid"
  sql += [f"insert into auth.users(id,email,email_confirmed_at,aud,role,raw_user_meta_data) select {expr},'capacity-'||g||'@mbstu.ac.bd',now(),'authenticated','authenticated','{{\"capacity_fixture\":true}}'::jsonb from generate_series({first},{last}) g;",f"insert into classmate.profiles(id,email,full_name,student_id,role,department_id,batch_id,verification_status) select {expr},'capacity-'||g||'@mbstu.ac.bd','Load Student '||lpad(g::text,4,'0'),'LT'||lpad(g::text,5,'0'),'student','{dept}','{bid}','active' from generate_series({first},{last}) g;",f"insert into classmate.device_tokens(profile_id,fcm_token,app_version) select {expr},'classmate-load:healthy:'||({expr})::text,18 from generate_series({first},{last}) g;"]
  for _ in range(size):
   number+=1;pid=uid(6,number)
   if len(users)<500:users.append({'id':pid,'batch':bid,'offering':oid,'semester':sid,'notice':uid(7,batch*100+1)})
  author=uid(6,number-size+1)
  for n in range(1,51):
   nid=uid(7,batch*100+n)
   sql.append(f"insert into classmate.notices(id,department_id,batch_id,author_id,title,body,silent) values('{nid}','{dept}','{bid}','{author}','Load notice {n}','Synthetic academic notice for isolated capacity tests. No real alert is sent.',true);")
 sql+= ["insert into classmate.notice_reads(notice_id,profile_id) select n.id,p.id from classmate.notices n join classmate.profiles p on p.batch_id=n.batch_id where n.id::text like 'd5202610-%';","commit;"]
 query(STAGING,'\n'.join(sql))
 keys=api_keys(STAGING);secret=management('projects/'+STAGING+'/postgrest')['jwt_secret']
 for user in users:user['token']=sign(secret,user['id'])
 output=Path('build/capacity-private.json');output.write_text(json.dumps({'ref':STAGING,'anon':keys['anon'],'users':users}),encoding='utf-8')
 print('Seeded 5,000 staging users, 200 notices, 250,000 read receipts; private test tokens kept in ignored build directory.')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['seed','cleanup']);args=parser.parse_args()
 if args.mode=='seed':seed()
 else:cleanup();Path('build/capacity-private.json').unlink(missing_ok=True);print('Tagged staging fixtures removed.')
