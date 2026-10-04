"""One controlled owner read-only Gemini check; removes only its own temporary chat."""
import sys,json,pathlib,urllib.request,urllib.error,uuid,time,base64,hmac,hashlib
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,management,api_keys,PRODUCTION
owner=query(PRODUCTION,"select p.id from classmate.profiles p join classmate.app_owners o on lower(p.email)=o.email where o.active and p.verification_status='active' limit 1")[0]['id']
batch=query(PRODUCTION,"select id from classmate.batches where is_active order by created_at limit 1")[0]['id']
secret=management('projects/'+PRODUCTION+'/postgrest')['jwt_secret']
def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).decode().rstrip('=')
token=enc({'alg':'HS256','typ':'JWT'})+'.'+enc({'aud':'authenticated','role':'authenticated','sub':owner,'iss':'https://'+PRODUCTION+'.supabase.co/auth/v1','exp':int(time.time())+300,'iat':int(time.time())})
token+='.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),token.encode(),hashlib.sha256).digest()).decode().rstrip('=')
headers={'apikey':api_keys(PRODUCTION)['anon'],'Authorization':'Bearer '+token,'Content-Type':'application/json','Content-Profile':'classmate','Origin':'https://classmatebd.vercel.app'}
conversation=str(uuid.uuid4());request_id=str(uuid.uuid4())
def post(path,body):
 req=urllib.request.Request('https://'+PRODUCTION+'.supabase.co/'+path,data=json.dumps(body).encode(),headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=90) as r:return r.status,json.loads(r.read())
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
try:
 status,_=post('rest/v1/rpc/manage_ai_conversation',{'target_id':conversation,'target_batch':batch,'operation':'create','target_title':'Temporary rollout read check'})
 assert status==200,('Create',status)
 started=time.monotonic()
 status,data=post('functions/v1/classmate-ai',{'mode':'agent','request_id':request_id,'conversation_id':conversation,'batch_id':batch,'text':'From the shared university bus schedules in the supplied database context, what is the earliest campus departure on an office-open day? Answer briefly. Do not propose or execute any changes.'})
 if status!=200:print(json.dumps({'generation_status':status,'message':data.get('message','Request failed')}));sys.exit(1)
 plan=data.get('plan') or data.get('result');assert plan and plan['actions']==[],'Unexpected mutation proposal'
 page_status,messages=post('rest/v1/rpc/ai_message_page',{'target_conversation':conversation})
 assert page_status==200 and len(messages)==2,'Both messages were not stored'
 assert messages[1]['content']==plan['message'],'Stored answer differs'
 print(json.dumps({'real_gemini_read':'passed','persisted_messages':len(messages),'actions':len(plan['actions']),'seconds':round(time.monotonic()-started,1)}))
finally:
 query(PRODUCTION,f"delete from classmate.ai_conversations where id='{conversation}' and user_id='{owner}';delete from classmate.ai_requests where id='{request_id}' and user_id='{owner}';")
