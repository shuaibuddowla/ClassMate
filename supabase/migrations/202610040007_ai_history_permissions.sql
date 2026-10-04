begin;
-- Protect legacy owner results created before the server added the authority tag.
update classmate.ai_requests r set plan=plan||jsonb_build_object('owner_only',true)
where plan is not null and not (plan?'owner_only') and exists(
 select 1 from classmate.profiles p join classmate.app_owners o on lower(o.email)=lower(p.email) where p.id=r.user_id);
drop policy ai_request_own on classmate.ai_requests;
create policy ai_request_own on classmate.ai_requests for select to authenticated using(
 user_id=auth.uid() and classmate.is_active() and classmate.can_read_batch(batch_id)
 and (not coalesce((plan->>'owner_only')::boolean,false) or classmate.is_owner()));
create or replace function classmate.start_ai_request(target_id uuid,target_batch uuid,target_mode text,target_hash text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare r classmate.ai_requests; attempts integer;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Batch access denied' using errcode='42501'; end if;
  if target_mode not in ('agent','compose','translate') then raise exception 'Unknown AI mode'; end if;
  if target_mode='compose' and not classmate.ai_can_write(target_batch) then raise exception 'Admin or batch CR required' using errcode='42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,0));
  select * into r from classmate.ai_requests where id=target_id for update;
  if found then
    if r.user_id<>auth.uid() or r.batch_id<>target_batch or r.mode<>target_mode or r.input_hash<>target_hash then raise exception 'AI request conflict' using errcode='42501'; end if;
    if coalesce((r.plan->>'owner_only')::boolean,false) and not classmate.is_owner() then
      raise exception 'Owner-only AI history access denied' using errcode='42501'; end if;
    if r.status in ('ready','complete') then return to_jsonb(r); end if;
    if r.status='generating' and r.updated_at>now()-interval '90 seconds' then raise exception 'This AI request is still running'; end if;
  end if;
  -- Failed retries are attempts too; replaying a ready/complete result costs nothing.
  insert into classmate.ai_rate_limits(user_id) values(auth.uid())
  on conflict(user_id) do update set
   calls=case when classmate.ai_rate_limits.window_start<=now()-interval '1 minute' then 1 else classmate.ai_rate_limits.calls+1 end,
   window_start=case when classmate.ai_rate_limits.window_start<=now()-interval '1 minute' then now() else classmate.ai_rate_limits.window_start end
  returning calls into attempts;
  if attempts>10 then raise exception 'Please wait a minute before another AI request'; end if;
  if r.id is not null then
    update classmate.ai_requests set status='generating',lease=gen_random_uuid(),updated_at=now() where id=target_id returning * into r;
  else
    insert into classmate.ai_requests(id,user_id,batch_id,mode,input_hash) values(target_id,auth.uid(),target_batch,target_mode,target_hash) returning * into r;
  end if;
  return to_jsonb(r);
end $$;
create or replace function classmate.execute_ai_request(target_id uuid) returns jsonb
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
    when 'post_notice' then outcome:=to_jsonb(classmate.post_notice((a->>'target_batch')::uuid,(a->>'target_course')::uuid,(a->>'notice_title')::text,(a->>'notice_body')::text));
    when 'ai_post_cancellation_notice' then outcome:=to_jsonb(classmate.ai_post_cancellation_notice((a->>'target_batch')::uuid,(a->>'target_course')::uuid,(a->>'change_date')::date,coalesce((a->>'target_silent')::boolean,false)));
    when 'edit_notice' then outcome:=to_jsonb(classmate.edit_notice((a->>'target_id')::uuid,(a->>'target_title')::text,(a->>'target_body')::text));
    when 'delete_notice' then outcome:=to_jsonb(classmate.delete_notice((a->>'target_id')::uuid));
    when 'save_routine_slot' then outcome:=to_jsonb(classmate.save_routine_slot((a->>'target_id')::uuid,(a->>'target_semester_course')::uuid,(a->>'target_day')::smallint,(a->>'target_start')::time without time zone,(a->>'target_end')::time without time zone,(a->>'target_room')::text));
    when 'delete_routine_slot' then outcome:=to_jsonb(classmate.delete_routine_slot((a->>'target_id')::uuid));
    when 'save_student_bus_schedule' then outcome:=to_jsonb(classmate.save_student_bus_schedule((a->>'target_id')::uuid,(a->>'target_kind')::text,(a->>'target_campus_departure')::time without time zone,(a->>'target_city_departure')::time without time zone,coalesce((a->>'target_active')::boolean,true)));
    when 'save_calendar_event' then outcome:=to_jsonb(classmate.save_calendar_event((a->>'target_id')::uuid,(a->>'target_title')::text,(a->>'target_start')::date,(a->>'target_end')::date,(a->>'target_scope')::text));
    when 'delete_calendar_event' then outcome:=to_jsonb(classmate.delete_calendar_event((a->>'target_id')::uuid));
    when 'save_batch_course' then outcome:=to_jsonb(classmate.save_batch_course((a->>'target_batch')::uuid,(a->>'target_offering')::uuid,(a->>'target_code')::text,(a->>'target_title')::text,(a->>'target_teacher_name')::text,(a->>'target_teacher_record')::uuid,(a->>'target_credit')::numeric,(a->>'target_catalog_course')::uuid));
    when 'remove_batch_course' then outcome:=to_jsonb(classmate.remove_batch_course((a->>'target_offering')::uuid));
    when 'delete_global_course' then outcome:=to_jsonb(classmate.delete_global_course((a->>'target_course')::uuid));
    when 'create_department' then outcome:=to_jsonb(classmate.create_department((a->>'target_name')::text,(a->>'target_code')::text));
    when 'configure_department' then outcome:=to_jsonb(classmate.configure_department((a->>'target_department')::uuid,(a->>'target_prefix')::text,(a->>'target_offset')::smallint,(a->>'target_active')::boolean));
    when 'create_batch' then outcome:=to_jsonb(classmate.create_batch((a->>'target_department')::uuid,(a->>'target_batch_number')::smallint,(a->>'target_session')::smallint));
    when 'publish_semester' then outcome:=to_jsonb(classmate.publish_semester((a->>'target_semester')::uuid));
    when 'clone_semester' then outcome:=to_jsonb(classmate.clone_semester((a->>'from_id')::uuid,(a->>'to_id')::uuid));
    when 'assign_cr' then outcome:=to_jsonb(classmate.assign_cr((a->>'target_profile')::uuid,(a->>'target_batch')::uuid,(a->>'valid_until')::timestamp with time zone));
    when 'revoke_cr' then outcome:=to_jsonb(classmate.revoke_cr((a->>'target_profile')::uuid));
    when 'approve_student_profile' then outcome:=to_jsonb(classmate.approve_student_profile((a->>'target_profile')::uuid,(a->>'corrected_student_id')::text,(a->>'target_batch')::uuid,(a->>'corrected_session')::smallint));
    when 'reject_student_profile' then outcome:=to_jsonb(classmate.reject_student_profile((a->>'target_profile')::uuid,(a->>'reason')::text));
    when 'owner_create_teacher' then outcome:=to_jsonb(classmate.owner_create_teacher((a->>'target_department')::uuid,(a->>'target_name')::text,(a->>'target_email')::text));
    when 'owner_save_teacher' then outcome:=to_jsonb(classmate.owner_save_teacher((a->>'target_record')::uuid,(a->>'target_name')::text,(a->>'target_email')::text));
    when 'owner_assign_teacher_record' then outcome:=to_jsonb(classmate.owner_assign_teacher_record((a->>'target_record')::uuid,(a->>'target_offering')::uuid));
    when 'set_teacher_allowlist' then outcome:=to_jsonb(classmate.set_teacher_allowlist((a->>'target_email')::text,(a->>'target_department')::uuid,(a->>'target_active')::boolean));
    when 'assign_teacher_to_course' then outcome:=to_jsonb(classmate.assign_teacher_to_course((a->>'target_teacher')::uuid,(a->>'target_semester_course')::uuid));
    when 'edit_resource_metadata' then outcome:=to_jsonb(classmate.edit_resource_metadata((a->>'target_resource')::uuid,(a->>'target_title')::text,(a->>'target_category')::text,(a->>'target_course')::uuid));
    when 'ai_delete_resource' then outcome:=to_jsonb(classmate.ai_delete_resource((a->>'target_resource')::uuid));
    when 'retry_failed_notifications' then outcome:=to_jsonb(classmate.retry_failed_notifications((a->>'target_event')::uuid));
    else raise exception 'Unsupported AI action';
  end case;
  results:=results||jsonb_build_array(jsonb_build_object('name',name,'result',outcome));
 end loop;
 outcome:=jsonb_build_object('message',r.plan->>'message','actions',results,'request_id',r.id);
 update classmate.ai_requests set status='complete',result=outcome,updated_at=now() where id=r.id;
 insert into classmate.audit_log(actor_id,action,target_id,details) values(auth.uid(),'execute_ai_request',r.id,jsonb_build_object('action_count',jsonb_array_length(results)));
 return outcome;
end $$;
notify pgrst,'reload schema';
commit;
