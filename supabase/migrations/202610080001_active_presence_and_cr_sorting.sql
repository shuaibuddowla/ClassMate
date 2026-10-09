begin;

-- Active presence tracking for students and admins
create table if not exists classmate.active_presence (
  profile_id uuid primary key references classmate.profiles(id) on delete cascade,
  last_seen_at timestamptz not null default now()
);

create index if not exists active_presence_seen_idx on classmate.active_presence(last_seen_at);
alter table classmate.active_presence enable row level security;
revoke all on classmate.active_presence from public, anon, authenticated;
grant all on classmate.active_presence to service_role;

-- Heartbeat function called by client apps while active
create or replace function classmate.touch_presence() returns void
language plpgsql security definer set search_path='' as $$
begin
  if not classmate.is_active() then return; end if;
  insert into classmate.active_presence(profile_id, last_seen_at)
  values (auth.uid(), now())
  on conflict (profile_id) do update set last_seen_at = now();
end $$;

revoke all on function classmate.touch_presence() from public, anon;
grant execute on function classmate.touch_presence() to authenticated;

-- Drop prior function signature to allow adding is_online column to the return table
drop function if exists classmate.batch_friends(uuid,text,integer);

-- batch_friends returns active students and admin in batch with presence and CR-first sorting
create or replace function classmate.batch_friends(target_batch uuid,query_text text default '',result_offset integer default 0)
returns table(profile_id uuid,full_name text,student_id text,role text,is_cr boolean,avatar_url text,is_online boolean)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'This batch directory is unavailable' using errcode='42501'; end if;
  if result_offset<0 or result_offset is null or length(query_text)>100 then raise exception 'Invalid directory query' using errcode='22023'; end if;
  return query select p.id,p.full_name,p.student_id,p.role::text,p.is_cr,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,
    exists(select 1 from classmate.active_presence ap where ap.profile_id=p.id and ap.last_seen_at > now() - interval '60 seconds')
    from classmate.profiles p join auth.users u on u.id=p.id
    where p.verification_status='active' and (
      (p.role='student' and p.batch_id=target_batch and p.department_id=(select department_id from classmate.batches where id=target_batch))
      or (p.role='admin' and exists(select 1 from classmate.app_owners o where o.active and o.email=p.email)
        and exists(select 1 from classmate.batches b join classmate.departments d on d.id=b.department_id
          where b.id=target_batch and (
            p.batch_id=b.id or (p.batch_id is null
              and p.email ~ '^[a-z]+[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$'
              and d.email_prefix=substring(p.email from '^([a-z]+)[0-9]')
              and b.academic_session=substring(p.email from '^[a-z]+([0-9]{2})')::integer
              and b.batch_number=b.academic_session-d.session_offset)))))
      and position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')))>0
    order by (p.role='admin') desc,(p.is_cr) desc,p.full_name,p.id limit 100 offset result_offset;
end $$;

revoke all on function classmate.batch_friends(uuid,text,integer) from public,anon;
grant execute on function classmate.batch_friends(uuid,text,integer) to authenticated;

notify pgrst,'reload schema';
commit;
