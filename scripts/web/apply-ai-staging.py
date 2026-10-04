import sys,pathlib,json,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
s=(root/'supabase/migrations/202610040006_classmate_ai.sql').read_text(encoding='utf-8')
sql="begin; drop function if exists classmate.ai_save_batch_bus(uuid,uuid,text,time,time,boolean); drop function if exists classmate.batch_bus_schedule_list(uuid); drop table if exists classmate.batch_bus_schedules;\n"
start=s.index('create table classmate.ai_rate_limits');end=s.index('create function classmate.start_ai_request',start)
sql+=s[start:end].replace('create table','create table if not exists',1)
for name in ['ai_context','start_ai_request','ai_post_cancellation_notice','set_ai_cooldown','execute_ai_request']:
 start=s.index('create function classmate.'+name+'(');end=s.index('end $$;',start)+len('end $$;')
 sql+=s[start:end].replace('create function','create or replace function',1)+'\n'
# Preserve later permission guards and the set-returning catalog correction.
for version,name in [('202610040007','ai_history_permissions'),('202610040009','ai_context_courses')]:
 patch=(root/f'supabase/migrations/{version}_{name}.sql').read_text(encoding='utf-8')
 sql+=patch.removeprefix('begin;').replace('commit;','')+'\n'
sql+='''revoke all on function classmate.ai_post_cancellation_notice(uuid,uuid,date,boolean) from public,anon;
grant execute on function classmate.ai_post_cancellation_notice(uuid,uuid,date,boolean) to authenticated;
revoke all on function classmate.set_ai_cooldown(text,timestamptz) from public,anon,authenticated;
grant execute on function classmate.set_ai_cooldown(text,timestamptz) to service_role;
'''
sql+=s[s.index('revoke all on function classmate.ai_can_write(uuid)'):]
comments=(root/'supabase/migrations/202610040005_social_comments.sql').read_text(encoding='utf-8')
updates='begin;\n'
for name in ['comment_page','save_notice_comment']:
 start=comments.index('create function classmate.'+name+'(');end=comments.index('end $$;',start)+len('end $$;')
 updates+=comments[start:end].replace('create function','create or replace function',1)+'\n'
updates+="notify pgrst,'reload schema';commit;"
try:
 query(STAGING,sql)
 query(STAGING,updates)
 print('Staging updated: shared buses, AI context, fixed dispatcher and silent cancellations.')
except urllib.error.HTTPError as e:print(e.read().decode());raise
