begin;
create function classmate.ai_can_write(target_batch uuid) returns boolean
language sql stable security definer set search_path='' as $$
  select classmate.is_owner() or exists(select 1 from classmate.profiles p where p.id=auth.uid()
    and p.role='student' and p.verification_status='active' and p.is_cr and p.batch_id=target_batch and p.cr_batch_id=target_batch
    and (p.cr_valid_until is null or p.cr_valid_until>now()));
$$;
create table classmate.ai_requests (
  id uuid primary key, user_id uuid not null references classmate.profiles(id) on delete cascade,
  batch_id uuid references classmate.batches(id), mode text not null check(mode in ('compose','agent','translate')),
  input_hash text not null, status text not null default 'generating',lease uuid not null default gen_random_uuid(),
  plan jsonb, result jsonb, created_at timestamptz not null default now(),updated_at timestamptz not null default now()
);
create index ai_request_user_time on classmate.ai_requests(user_id,created_at desc);
alter table classmate.ai_requests enable row level security;
create policy ai_request_own on classmate.ai_requests for select to authenticated using(user_id=auth.uid());
revoke all on classmate.ai_requests from public,anon,authenticated;
grant select on classmate.ai_requests to authenticated;
create table classmate.ai_translations (
  notice_id uuid not null references classmate.notices(id) on delete cascade,source_hash text not null,
  title text not null,body text not null,created_at timestamptz not null default now(),primary key(notice_id,source_hash)
);
alter table classmate.ai_translations enable row level security;
create policy translation_reader on classmate.ai_translations for select to authenticated using(classmate.can_read_notice(notice_id));
revoke all on classmate.ai_translations from public,anon,authenticated;
grant select on classmate.ai_translations to authenticated;
create table classmate.ai_provider_cooldowns (quota_group text primary key, retry_at timestamptz not null);
revoke all on classmate.ai_provider_cooldowns from public,anon,authenticated;
grant all on classmate.ai_provider_cooldowns,classmate.ai_translations to service_role;

create table classmate.ai_rate_limits (
 user_id uuid primary key references classmate.profiles(id) on delete cascade,
 window_start timestamptz not null default now(),calls integer not null default 1
);
alter table classmate.ai_rate_limits enable row level security;
revoke all on classmate.ai_rate_limits from public,anon,authenticated;

create function classmate.start_ai_request(target_id uuid,target_batch uuid,target_mode text,target_hash text) returns jsonb
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
create function classmate.finish_ai_request(target_id uuid,target_lease uuid,target_plan jsonb,target_result jsonb default null)
returns void language plpgsql security definer set search_path='' as $$
begin
  if coalesce(jsonb_array_length(target_plan->'actions'),0)>40 then raise exception 'Too many actions'; end if;
  update classmate.ai_requests set plan=target_plan,result=target_result,status=case when target_result is null then 'ready' else 'complete' end,updated_at=now()
    where id=target_id and lease=target_lease and status='generating';
  if not found then raise exception 'AI request lease expired'; end if;
end $$;
create function classmate.fail_ai_request(target_id uuid,target_lease uuid) returns void
language sql security definer set search_path='' as $$
  update classmate.ai_requests set status='failed',updated_at=now() where id=target_id and lease=target_lease and status='generating';
$$;

create function classmate.ai_context(target_batch uuid,query_text text default '') returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare owner boolean:=classmate.is_owner(); answer jsonb;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Batch access denied' using errcode='42501'; end if;
  answer:=jsonb_build_object('today',(now() at time zone 'Asia/Dhaka')::date,'batch_id',target_batch,
    'owner',owner,'can_write',classmate.ai_can_write(target_batch),'courses',classmate.batch_course_catalog(target_batch),
    'buses',coalesce((select jsonb_agg(to_jsonb(b) order by b.departure_time) from classmate.bus_schedules b where b.active),'[]'),
    'routine',coalesce((select jsonb_agg(to_jsonb(q)) from (select r.* from classmate.routine_slots r
      join classmate.semester_courses c on c.id=r.semester_course_id join classmate.semesters s on s.id=c.semester_id
      where s.batch_id=target_batch and s.status='active' order by r.day_of_week,r.start_time limit 100) q),'[]'),
    'calendar',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,start_date,end_date,scope from classmate.academic_calendar_events
      where end_date>=(now() at time zone 'Asia/Dhaka')::date-30 order by start_date limit 60) q),'[]'),
    'notices',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,body,semester_course_id,class_change_id,published_at from classmate.notices
      where batch_id=target_batch and classmate.can_read_notice(id) and (query_text='' or title ilike '%'||query_text||'%')
      order by published_at desc limit 20) q),'[]'),
    'files',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,title,category,semester_course_id from classmate.file_metadata
      where batch_id=target_batch and status='active' and (query_text='' or title ilike '%'||query_text||'%')
        and (semester_course_id is null or classmate.can_manage_course(semester_course_id)
          or exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='student' and p.batch_id=target_batch))
        and (semester_course_id is null or exists(select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id where sc.id=semester_course_id and s.status in ('active','completed')))
      limit 50) q),'[]'));
  if owner then
    answer:=answer||jsonb_build_object('teachers',classmate.owner_teachers(),'health',classmate.system_health(),
      'departments',coalesce((select jsonb_agg(to_jsonb(d)) from classmate.departments d),'[]'),
      'batches',coalesce((select jsonb_agg(to_jsonb(b)) from classmate.batches b),'[]'),
      'semesters',coalesce((select jsonb_agg(to_jsonb(s)) from classmate.semesters s where batch_id=target_batch),'[]'),
      'people',coalesce((select jsonb_agg(to_jsonb(q)) from (select id,email,full_name,student_id,batch_id,role,is_cr,verification_status
        from classmate.profiles where query_text='' or full_name ilike '%'||query_text||'%' or email ilike '%'||query_text||'%' order by id limit 50) q),'[]'));
  end if;
  return answer;
end $$;
create function classmate.ai_delete_resource(target_resource uuid) returns boolean
language plpgsql security definer set search_path='' as $$
declare key text;
begin
  perform classmate.require_owner();
  select r2_object_key into key from classmate.file_metadata where id=target_resource for update;
  if not found then return false; end if;
  insert into classmate.resource_cleanup_jobs(object_key) values(key) on conflict do nothing;
  update classmate.file_metadata set status='archived' where id=target_resource;
  return classmate.finalize_resource_deletion(target_resource);
end $$;

create function classmate.ai_post_cancellation_notice(target_batch uuid,target_course uuid,change_date date,target_silent boolean default false)
returns classmate.notices language plpgsql security definer set search_path='' as $$
declare saved classmate.notices; existed boolean;
begin
 -- Serialize duplicate cancellation requests so silent mode never alters an older notice.
 perform pg_advisory_xact_lock(hashtextextended(target_batch::text||target_course::text||change_date::text,0));
 select exists(select 1 from classmate.notices n join classmate.class_changes c on c.id=n.class_change_id
   where c.batch_id=target_batch and c.semester_course_id=target_course and c.effective_date=change_date and c.kind='cancelled') into existed;
 saved:=classmate.post_cancellation_notice(target_batch,target_course,change_date);
 if target_silent and not existed then
   update classmate.notices set title='/silent '||title where id=saved.id returning * into saved;
   delete from classmate.notification_outbox where kind='notice' and record_id=saved.id;
 end if;
 return saved;
end $$;
revoke all on function classmate.ai_post_cancellation_notice(uuid,uuid,date,boolean) from public,anon;
grant execute on function classmate.ai_post_cancellation_notice(uuid,uuid,date,boolean) to authenticated;

create function classmate.set_ai_cooldown(target_group text,target_until timestamptz) returns void
language plpgsql security definer set search_path='' as $$
begin
 insert into classmate.ai_provider_cooldowns(quota_group,retry_at) values(target_group,target_until)
 on conflict(quota_group) do update set retry_at=greatest(classmate.ai_provider_cooldowns.retry_at,excluded.retry_at);
end $$;
revoke all on function classmate.set_ai_cooldown(text,timestamptz) from public,anon,authenticated;
grant execute on function classmate.set_ai_cooldown(text,timestamptz) to service_role;

-- The fixed action dispatcher
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
revoke all on function classmate.ai_can_write(uuid),
 classmate.start_ai_request(uuid,uuid,text,text),classmate.execute_ai_request(uuid),classmate.ai_context(uuid,text),classmate.ai_delete_resource(uuid) from public,anon;
grant execute on function classmate.ai_can_write(uuid),
 classmate.start_ai_request(uuid,uuid,text,text),classmate.execute_ai_request(uuid),classmate.ai_context(uuid,text),classmate.ai_delete_resource(uuid) to authenticated;
revoke all on function classmate.finish_ai_request(uuid,uuid,jsonb,jsonb),classmate.fail_ai_request(uuid,uuid) from public,anon,authenticated;
grant execute on function classmate.finish_ai_request(uuid,uuid,jsonb,jsonb),classmate.fail_ai_request(uuid,uuid) to service_role;
notify pgrst,'reload schema';
commit;
