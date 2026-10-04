"""Staging-only chat storage/RLS checks. Every test write rolls back."""
import sys,json,pathlib,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
f=json.loads((root/'build/web-fixtures-private.json').read_text(encoding='utf-8'))
student,other,cr,teacher=f['users']; batch=f['batch']
def identity(u):return "select set_config('request.jwt.claims',"+repr(json.dumps({'sub':u['id'],'email':u.get('email',''),'role':'authenticated'}))+",true);"
sql='begin;'+identity(student)+f"""
do $$ declare conv uuid:='ae042026-0040-4000-8000-000000000001'; req uuid; r jsonb; hist jsonb; i int;
begin
 insert into classmate.courses(id,department_id,course_code,course_title,course_type)
 select 'ae042026-0042-4000-8000-000000000001',department_id,'CHAT2','Second AI regression course','theory' from classmate.batches where id='{batch}';
 insert into classmate.semester_courses(semester_id,course_id)
 select id,'ae042026-0042-4000-8000-000000000001' from classmate.semesters where batch_id='{batch}' and status='active';
 assert jsonb_array_length(classmate.ai_context('{batch}')->'courses')=2,'Multi-course context aggregation';
 perform classmate.manage_ai_conversation(conv,'{batch}','create');
 for i in 1..26 loop
  delete from classmate.ai_rate_limits where user_id=auth.uid();
  req:=gen_random_uuid();r:=classmate.start_ai_request(req,'{batch}','agent','hash-'||i);
  hist:=classmate.attach_ai_chat_turn(req,conv,'Academic question '||i);
  assert jsonb_array_length(hist)<=8,'Bounded model history';
  perform classmate.attach_ai_chat_turn(req,conv,'Academic question '||i);
  perform classmate.finish_ai_request(req,(r->>'lease')::uuid,jsonb_build_object('message','Database answer '||i,'actions','[]'::jsonb),jsonb_build_object('message','Answer'));
  assert (select count(*) from classmate.ai_messages where request_id=req)=2,'Exactly one user and assistant message';
 end loop;
 assert jsonb_array_length(classmate.ai_message_page(conv))=50,'Reader page bounded to 50';
 assert jsonb_array_length(classmate.ai_message_page(conv,(classmate.ai_message_page(conv)->0->>'id')::bigint))=2,'Earlier history preserved';
 perform classmate.manage_ai_conversation(conv,'{batch}','rename','Saved academic conversation');
 assert (select title='Saved academic conversation' from classmate.ai_conversations where id=conv),'Rename saved';
 assert not has_table_privilege('authenticated','classmate.ai_messages','INSERT'),'Cannot forge assistant';
 assert not has_table_privilege('authenticated','classmate.ai_conversations','UPDATE'),'Conversation ownership protected';
 assert not classmate.ai_can_write('{batch}'),'Student read-only';
 execute 'set local role authenticated';
 assert (select count(*) from classmate.ai_conversation_page('{batch}'))=1,'Owner sees own conversation';
 execute 'reset role';
 -- A model write plan cannot grant a student database write authority.
 req:=gen_random_uuid();r:=classmate.start_ai_request(req,'{batch}','agent','forged-write');
 perform classmate.finish_ai_request(req,(r->>'lease')::uuid,jsonb_build_object('message','Forbidden','actions',jsonb_build_array(jsonb_build_object('name','post_notice','args',jsonb_build_object('target_batch','{batch}')))));
 begin perform classmate.execute_ai_request(req);raise exception 'Student write allowed';exception when insufficient_privilege then null;end;
end $$;
"""+identity(cr)+f"""
set local role authenticated;
do $$ begin
 assert jsonb_array_length(classmate.ai_message_page('ae042026-0040-4000-8000-000000000001'))=0,'Same-batch user cannot read others history';
 assert not exists(select 1 from classmate.ai_conversations),'No cross-account conversations';
 begin perform classmate.manage_ai_conversation('ae042026-0040-4000-8000-000000000001','{batch}','delete');raise exception 'Other account deletion allowed';exception when insufficient_privilege then null;end;
end $$;
reset role;
"""+identity(other)+f"""
set local role authenticated;
do $$ begin
 assert jsonb_array_length(classmate.ai_message_page('ae042026-0040-4000-8000-000000000001'))=0,'Cross-batch messages hidden';
 begin perform classmate.manage_ai_conversation(gen_random_uuid(),'{batch}','create');raise exception 'Cross batch chat allowed';exception when insufficient_privilege then null;end;
end $$;
reset role;
"""+identity(student)+f"""
update classmate.ai_conversations set owner_only=true where id='ae042026-0040-4000-8000-000000000001';
set local role authenticated;
do $$ begin
 assert jsonb_array_length(classmate.ai_message_page('ae042026-0040-4000-8000-000000000001'))=0,'Revoked owner history hidden';
end $$;
reset role;
update classmate.ai_conversations set owner_only=false where id='ae042026-0040-4000-8000-000000000001';
select classmate.manage_ai_conversation('ae042026-0040-4000-8000-000000000001','{batch}','delete');
do $$ begin assert not exists(select 1 from classmate.ai_messages),'Deleted chat messages removed';end $$;
"""+identity(f['owner'])+f"""
do $$ declare empty_batch uuid:='ae042026-0043-4000-8000-000000000001';begin
 insert into classmate.batches(id,department_id,batch_number,academic_session)
 select empty_batch,b.department_id,(28-d.session_offset)::smallint,28 from classmate.batches b join classmate.departments d on d.id=b.department_id where b.id='{batch}';
 assert classmate.ai_context(empty_batch)->'courses'='[]'::jsonb,'Empty course catalog is still a valid context';
end $$;
select 'Passed: per-account history, cross-batch denial, 50-message pages, idempotency, trusted replies, student write denial, owner revocation, rename/delete' as result;
rollback;
"""
try:
 result=query(STAGING,sql);print(result[-1] if result else 'Chat checks passed')
except urllib.error.HTTPError as e:print(e.read().decode());raise
