begin;

-- 1. Update check constraint on classmate.ai_requests
alter table classmate.ai_requests drop constraint if exists ai_requests_mode_check;
alter table classmate.ai_requests add constraint ai_requests_mode_check
  check (mode in ('compose', 'agent', 'translate', 'batch_fund_parse', 'auto_notice'));

-- 2. Update start_ai_request RPC to allow 'auto_notice'
create or replace function classmate.start_ai_request(target_id uuid, target_batch uuid, target_mode text, target_hash text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare r classmate.ai_requests; attempts integer;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Batch access denied' using errcode='42501'; end if;
  if target_mode not in ('agent','compose','translate','batch_fund_parse','auto_notice') then raise exception 'Unknown AI mode'; end if;
  if target_mode in ('compose','batch_fund_parse','auto_notice') and not classmate.ai_can_write(target_batch) then raise exception 'Admin or batch CR required' using errcode='42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,0));
  select * into r from classmate.ai_requests where id=target_id for update;
  if found then
    if r.user_id<>auth.uid() or r.batch_id<>target_batch or r.mode<>target_mode or r.input_hash<>target_hash then raise exception 'AI request conflict' using errcode='42501'; end if;
    if coalesce((r.plan->>'owner_only')::boolean,false) and not classmate.is_owner() then
      raise exception 'Owner-only AI history access denied' using errcode='42501'; end if;
    if r.status in ('ready','complete') then return to_jsonb(r); end if;
    if r.status='generating' and r.updated_at>now()-interval '90 seconds' then raise exception 'This AI request is still running'; end if;
  end if;
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

commit;
