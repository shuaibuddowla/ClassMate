"""Authenticated staging checks; all writes roll back. Never sends test alerts."""
import sys,json,pathlib,urllib.request,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
f=json.loads((root/'build/web-fixtures-private.json').read_text(encoding='utf-8'))
student,other,cr,teacher=f['users'];batch=f['batch'];notice=f['notice']
def identity(u):return "select set_config('request.jwt.claims',"+repr(json.dumps({'sub':u['id'],'email':u.get('email',''),'role':'authenticated'}))+",true);"
sql='begin;'+identity(student)+f'''
do $$ declare c classmate.notice_comments; r classmate.notice_comments; begin
 c:=classmate.save_notice_comment('{notice}','Original comment',null,null,'ae042026-0020-4000-8000-000000000001');
 r:=classmate.save_notice_comment('{notice}','Original comment',null,null,'ae042026-0020-4000-8000-000000000001');
 assert c.id=r.id,'Comment idempotency';
 perform classmate.set_comment_like(c.id,true); perform classmate.set_comment_like(c.id,true);
 assert (select count(*) from classmate.comment_likes where comment_id=c.id)=1,'Like idempotency';
 r:=classmate.save_notice_comment('{notice}','A reply',c.id);
 assert jsonb_array_length(classmate.comment_page('{notice}',c.id))=1,'Thread pagination';
 assert classmate.comment_page('{notice}')->0->>'author_name' is not null,'Real author name';
 perform classmate.save_notice_comment('{notice}','Edited comment',null,c.id);
 assert (select updated_at from classmate.notice_comments where id=c.id) is not null,'Edit timestamp';
 begin perform classmate.save_notice_comment('{f['other_notice']}','No cross batch'); raise exception 'Cross batch unexpectedly allowed'; exception when insufficient_privilege then null; end;
end $$;
'''+identity(other)+f'''
do $$ begin
 begin perform classmate.comment_page('{notice}');raise exception 'Reader leak';exception when insufficient_privilege then null;end;
 begin perform classmate.start_ai_request(gen_random_uuid(),'{batch}','agent','x');raise exception 'AI context leak';exception when insufficient_privilege then null;end;
end $$;
'''+identity(teacher)+f'''
do $$ begin
 begin perform classmate.start_ai_request(gen_random_uuid(),'{batch}','compose','x');raise exception 'Teacher AI write unexpectedly allowed';exception when insufficient_privilege then null;end;
end $$;
'''+identity(cr)+f'''
do $$ declare c uuid; r jsonb; id uuid; result1 jsonb; before_count int; begin
 select id into c from classmate.notice_comments where notice_id='{notice}' and parent_id is null limit 1;
 begin perform classmate.save_notice_comment('{notice}','Not my comment',null,c);raise exception 'Edit others allowed';exception when insufficient_privilege then null;end;
 begin perform classmate.delete_notice_comment(c);raise exception 'Delete others allowed';exception when insufficient_privilege then null;end;
 assert jsonb_array_length(classmate.ai_context('{batch}')->'buses')=(select count(*) from classmate.bus_schedules where active),'Shared buses in context';
 id:=gen_random_uuid();r:=classmate.start_ai_request(id,'{batch}','agent','bus');
 perform classmate.finish_ai_request(id,(r->>'lease')::uuid,jsonb_build_object('message','Shared campus bus','actions',jsonb_build_array(jsonb_build_object('name','save_student_bus_schedule','args',jsonb_build_object('target_id',null,'target_kind','office_open','target_campus_departure','05:18','target_city_departure','05:48','target_active',true)))));
 result1:=classmate.execute_ai_request(id);
 assert result1=classmate.execute_ai_request(id),'Execution idempotency';
 assert exists(select 1 from classmate.bus_schedules where departure_time='05:18' and city_departure_time='05:48' and active),'Shared bus persisted';
 select count(*) into before_count from classmate.bus_schedules;
 id:=gen_random_uuid();r:=classmate.start_ai_request(id,'{batch}','agent','atomic');
 perform classmate.finish_ai_request(id,(r->>'lease')::uuid,jsonb_build_object('message','Atomic','actions',jsonb_build_array(
  jsonb_build_object('name','save_student_bus_schedule','args',jsonb_build_object('target_id',null,'target_kind','office_open','target_campus_departure','05:19','target_city_departure','05:49','target_active',true)),
  jsonb_build_object('name','save_routine_slot','args',jsonb_build_object('target_id',null,'target_semester_course','{other['offering']}','target_day',0,'target_start','12:00','target_end','13:00','target_room','Test')))));
 begin perform classmate.execute_ai_request(id);raise exception 'Cross batch timetable allowed';exception when insufficient_privilege then null;end;
 assert (select count(*) from classmate.bus_schedules)=before_count,'All actions rolled back on denial';
 id:=gen_random_uuid();r:=classmate.start_ai_request(id,'{batch}','compose','silent');
 perform classmate.finish_ai_request(id,(r->>'lease')::uuid,jsonb_build_object('message','Silent','actions',jsonb_build_array(jsonb_build_object('name','post_notice','args',jsonb_build_object('target_batch','{batch}','target_course',null,'notice_title','/silent AI staging test','notice_body','Feed only')))));
 result1:=classmate.execute_ai_request(id);c:=(result1->'actions'->0->'result'->>'id')::uuid;
 assert (select silent from classmate.notices where id=c),'Silent compose';
 assert not exists(select 1 from classmate.notification_outbox where record_id=c),'No silent push';
 c:=(classmate.ai_post_cancellation_notice('{batch}','{cr['offering']}',(now() at time zone 'Asia/Dhaka')::date,true)).id;
 assert (select silent from classmate.notices where id=c),'Silent cancellation';
 assert not exists(select 1 from classmate.notification_outbox where record_id=c),'No cancellation push';
 assert exists(select 1 from classmate.class_changes where id=(select class_change_id from classmate.notices where id=c) and kind='cancelled'),'Real cancellation linked';
 id:=gen_random_uuid();r:=classmate.start_ai_request(id,'{batch}','agent','owner-history');
 perform classmate.finish_ai_request(id,(r->>'lease')::uuid,jsonb_build_object('message','Former owner data','actions','[]'::jsonb,'owner_only',true),jsonb_build_object('message','Private owner answer'));
 begin perform classmate.execute_ai_request(id);raise exception 'Former owner result leaked';exception when insufficient_privilege then null;end;
 begin perform classmate.start_ai_request(id,'{batch}','agent','owner-history');raise exception 'Former owner replay leaked';exception when insufficient_privilege then null;end;
 execute 'set local role authenticated';
 assert current_user='authenticated','Authenticated RLS test role';
 assert not exists(select 1 from classmate.ai_requests where coalesce((plan->>'owner_only')::boolean,false)),'AI history RLS';
 execute 'reset role';
 id:=gen_random_uuid();r:=classmate.start_ai_request(id,'{batch}','agent','revoked');
 perform classmate.finish_ai_request(id,(r->>'lease')::uuid,jsonb_build_object('message','Revocation','actions',jsonb_build_array(jsonb_build_object('name','post_notice','args',jsonb_build_object('target_batch','{batch}','target_course',null,'notice_title','/silent Should not post','notice_body','Test')))));
 update classmate.profiles set is_cr=false,cr_batch_id=null where id='{cr['id']}';
 begin perform classmate.execute_ai_request(id);raise exception 'Revoked CR allowed';exception when insufficient_privilege then null;end;
end $$;
'''+identity(student)+f'''
do $$ declare c uuid;begin
 select id into c from classmate.notice_comments where notice_id='{notice}' and parent_id is null limit 1;
 perform classmate.delete_notice_comment(c);
 assert not exists(select 1 from classmate.notice_comments where (id=c or parent_id=c) and deleted_at is null),'Soft delete reply cascade';
 assert not has_function_privilege('authenticated','classmate.finish_ai_request(uuid,uuid,jsonb,jsonb)','EXECUTE'),'Service only plan storage';
 assert not has_table_privilege('authenticated','classmate.ai_requests','UPDATE'),'No plan tampering';
 perform classmate.set_ai_cooldown('staging-verification',now()+interval '30 seconds');
 perform classmate.set_ai_cooldown('staging-verification',now()+interval '5 seconds');
 assert (select retry_at from classmate.ai_provider_cooldowns where quota_group='staging-verification')=now()+interval '30 seconds','Cooldown never shortened';
 assert not has_function_privilege('authenticated','classmate.set_ai_cooldown(text,timestamptz)','EXECUTE'),'Private provider controls';
end $$;
do $$ declare request_id uuid:=gen_random_uuid();r jsonb;i int;begin
 delete from classmate.ai_rate_limits where user_id=auth.uid();
 for i in 1..10 loop
  r:=classmate.start_ai_request(request_id,'{batch}','agent','rate');
  perform classmate.fail_ai_request(request_id,(r->>'lease')::uuid);
 end loop;
 begin perform classmate.start_ai_request(request_id,'{batch}','agent','rate');raise exception 'Retry limit missing';
 exception when raise_exception then if sqlerrm not like 'Please wait a minute%' then raise;end if;end;
end $$;
select 'Passed: comments, replies, likes, ownership, isolation, shared buses, idempotency, atomicity, silent compose/cancellation, revoked CR, service-only plans' as result;
rollback;
'''
try:
 print(query(STAGING,sql.replace('do $$','do $$\n#variable_conflict use_column')))
except urllib.error.HTTPError as e:print(e.read().decode());raise

# Real Edge authentication + configured-key fallback, without model calls.
u=cr
body={'mode':'compose','request_id':'ae042026-0022-4000-8000-000000000001','batch_id':batch,'text':'Test without a configured provider'}
req=urllib.request.Request(f'https://{STAGING}.supabase.co/functions/v1/classmate-ai',method='POST',data=json.dumps(body).encode(),headers={'apikey':f['anon'],'Authorization':'Bearer '+u['token'],'Content-Type':'application/json','Origin':'http://localhost:3000'})
try:
 with urllib.request.urlopen(req) as r:raise AssertionError('Expected provider-unconfigured response')
except urllib.error.HTTPError as e:
 data=json.loads(e.read());assert e.code==503 and 'not configured' in data['message'],data
 print('Authenticated staging Edge: missing Gemini keys handled safely (503).')
query(STAGING,"delete from classmate.ai_requests where id='ae042026-0022-4000-8000-000000000001'")
