"""Generate a fixed, typed dispatcher from the verified existing RPC signatures.
Input is an authenticated schema snapshot, never model output. No runtime SQL generation.
"""
import json,re,pathlib
root=pathlib.Path(__file__).resolve().parents[2]
specs={x['proname']:x for x in json.loads((root/'scripts/web/ai-rpc-signatures.json').read_text(encoding='utf-8'))}
allowed=['post_notice','ai_post_cancellation_notice','edit_notice','delete_notice','save_routine_slot','delete_routine_slot',
 'save_student_bus_schedule','save_calendar_event','delete_calendar_event','save_batch_course','remove_batch_course',
 'delete_global_course','create_department','configure_department','create_batch','publish_semester','clone_semester','assign_cr','revoke_cr',
 'approve_student_profile','reject_student_profile','owner_create_teacher','owner_save_teacher','owner_assign_teacher_record',
 'set_teacher_allowlist','assign_teacher_to_course','edit_resource_metadata','ai_delete_resource','retry_failed_notifications']
specs['ai_post_cancellation_notice']={'arguments':'target_batch uuid, target_course uuid, change_date date, target_silent boolean DEFAULT false'}
specs['ai_delete_resource']={'arguments':'target_resource uuid'}
cr=allowed[:7]
tools=[]; cases=[]
for name in allowed:
 args=[]; expressions=[]
 for raw in specs[name]['arguments'].split(','):
  arg,typ=raw.strip().split(' ',1); typ,_,default=typ.partition(' DEFAULT ')
  desc='HH:mm, 24-hour time' if typ.startswith('time without') else 'YYYY-MM-DD' if typ=='date' else 'Existing UUID; null only when creating or clearing' if typ=='uuid' else 'Numeric email ending year (25 means 24-25)' if arg.endswith('session') else typ
  if arg=='target_kind':desc='office_open or closed; shared university bus timetable'
  if arg=='target_scope':desc='university (classes and offices closed), classes (only classes closed), observance, or working_day'
  if arg=='target_category':desc='notes, slides, questions, syllabus, or other'
  if arg=='target_day':desc='Sunday=0, Monday=1, Tuesday=2, Wednesday=3, Thursday=4, Friday=5, Saturday=6'
  args.append({'name':arg,'type':typ,'description':desc,'optional':bool(default)})
  exp=f"(a->>'{arg}')::{typ}"
  if default and not default.startswith('NULL'):exp=f'coalesce({exp},{default})'
  expressions.append(exp)
 cases.append(f"    when '{name}' then outcome:=to_jsonb(classmate.{name}({','.join(expressions)}));")
 tools.append({'name':name,'cr':name in cr,'arguments':args,'destructive':name.startswith(('delete','remove','reject','revoke','clone')) or name=='ai_delete_resource'})
p=root/'supabase/migrations/202610040006_classmate_ai.sql'
s=p.read_text(encoding='utf-8').split('-- The fixed action dispatcher')[0].split('create function classmate.execute_ai_request')[0]
s+='''-- The fixed action dispatcher
create function classmate.execute_ai_request(target_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare r classmate.ai_requests; op jsonb; a jsonb; name text; outcome jsonb; results jsonb:='[]'; owner boolean:=classmate.is_owner();
begin
 select * into r from classmate.ai_requests where id=target_id and user_id=auth.uid() for update;
 if not found or not classmate.is_active() or not classmate.can_read_batch(r.batch_id) then raise exception 'AI request access denied' using errcode='42501'; end if;
 if coalesce((r.plan->>'owner_only')::boolean,false) and not owner then raise exception 'Owner-only AI history access denied' using errcode='42501'; end if;
 if r.status='complete' then return r.result; end if;
 if r.status<>'ready' then raise exception 'AI request is not ready'; end if;
 if jsonb_typeof(r.plan->'actions')<>'array' or jsonb_array_length(r.plan->'actions')>40 then raise exception 'Invalid action plan'; end if;
 if jsonb_array_length(r.plan->'actions')>0 and not classmate.ai_can_write(r.batch_id) then raise exception 'AI write access denied' using errcode='42501'; end if;
 if r.mode='compose' and (jsonb_array_length(r.plan->'actions')<>1 or r.plan->'actions'->0->>'name' not in ('post_notice','ai_post_cancellation_notice')) then
   raise exception 'Notice composer can post only one notice'; end if;
 if r.mode='compose' and (r.plan->'actions'->0->'args'->>'target_batch')::uuid is distinct from r.batch_id then
   raise exception 'Composer batch mismatch' using errcode='42501'; end if;
 for op in select value from jsonb_array_elements(r.plan->'actions') loop
  name:=op->>'name';a:=op->'args';
  if jsonb_typeof(a)<>'object' then raise exception 'Invalid action arguments'; end if;
  if not owner then
   if name not in ('post_notice','ai_post_cancellation_notice','edit_notice','delete_notice','save_routine_slot','delete_routine_slot','save_student_bus_schedule') then
    raise exception 'CR action denied' using errcode='42501'; end if;
   if name in ('post_notice','ai_post_cancellation_notice') and (a->>'target_batch')::uuid is distinct from r.batch_id then
    raise exception 'CR batch mismatch' using errcode='42501'; end if;
   if name in ('edit_notice','delete_notice') and not exists(select 1 from classmate.notices where id=(a->>'target_id')::uuid and batch_id=r.batch_id) then
    raise exception 'Notice batch mismatch' using errcode='42501'; end if;
   if name='save_routine_slot' and not exists(select 1 from classmate.semester_courses c join classmate.semesters s on s.id=c.semester_id
      where c.id=(a->>'target_semester_course')::uuid and s.batch_id=r.batch_id and s.status='active') then
    raise exception 'Routine batch mismatch' using errcode='42501'; end if;
   if name in ('save_routine_slot','delete_routine_slot') and nullif(a->>'target_id','') is not null and not exists(
     select 1 from classmate.routine_slots slot join classmate.semester_courses c on c.id=slot.semester_course_id
     join classmate.semesters s on s.id=c.semester_id where slot.id=(a->>'target_id')::uuid and s.batch_id=r.batch_id) then
    raise exception 'Routine row batch mismatch' using errcode='42501'; end if;
  end if;
  case name
'''+ '\n'.join(cases)+'''
    else raise exception 'Unsupported AI action';
  end case;
  results:=results||jsonb_build_array(jsonb_build_object('name',name,'result',outcome));
 end loop;
 outcome:=jsonb_build_object('message',r.plan->>'message','actions',results,'request_id',r.id);
 update classmate.ai_requests set status='complete',result=outcome,updated_at=now() where id=r.id;
 insert into classmate.audit_log(actor_id,action,target_id,details) values(auth.uid(),'execute_ai_request',r.id,jsonb_build_object('action_count',jsonb_array_length(results)));
 return outcome;
end $$;
revoke all on function classmate.ai_can_write(uuid),
 classmate.start_ai_request(uuid,uuid,text,text),classmate.execute_ai_request(uuid),classmate.ai_context(uuid,text),classmate.ai_delete_resource(uuid) from public,anon;
grant execute on function classmate.ai_can_write(uuid),
 classmate.start_ai_request(uuid,uuid,text,text),classmate.execute_ai_request(uuid),classmate.ai_context(uuid,text),classmate.ai_delete_resource(uuid) to authenticated;
revoke all on function classmate.finish_ai_request(uuid,uuid,jsonb,jsonb),classmate.fail_ai_request(uuid,uuid) from public,anon,authenticated;
grant execute on function classmate.finish_ai_request(uuid,uuid,jsonb,jsonb),classmate.fail_ai_request(uuid,uuid) to service_role;
notify pgrst,'reload schema';
commit;
'''
p.write_text(s,encoding='utf-8')
(root/'supabase/functions/classmate-ai').mkdir(exist_ok=True)
(root/'supabase/functions/classmate-ai/tools.ts').write_text('// Generated from verified RPC signatures by scripts/web/generate-ai-tools.py.\nexport const tools = '+json.dumps(tools,indent=2)+' as const;\n',encoding='utf-8')
history="""begin;
-- Protect legacy owner results created before the server added the authority tag.
update classmate.ai_requests r set plan=plan||jsonb_build_object('owner_only',true)
where plan is not null and not (plan?'owner_only') and exists(
 select 1 from classmate.profiles p join classmate.app_owners o on lower(o.email)=lower(p.email) where p.id=r.user_id);
drop policy ai_request_own on classmate.ai_requests;
create policy ai_request_own on classmate.ai_requests for select to authenticated using(
 user_id=auth.uid() and classmate.is_active() and classmate.can_read_batch(batch_id)
 and (not coalesce((plan->>'owner_only')::boolean,false) or classmate.is_owner()));
"""
for name in ['start_ai_request','execute_ai_request']:
 start=s.index('create function classmate.'+name+'(');end=s.index('end $$;',start)+len('end $$;')
 history+=s[start:end].replace('create function','create or replace function',1)+'\n'
history+="notify pgrst,'reload schema';\ncommit;\n"
(root/'supabase/migrations/202610040007_ai_history_permissions.sql').write_text(history,encoding='utf-8')
