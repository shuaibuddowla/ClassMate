begin;
create index notice_reads_page_idx on classmate.notice_reads(notice_id,read_at desc,profile_id);
create index profiles_batch_active_idx on classmate.profiles(batch_id,department_id) where verification_status='active';

create function classmate.notice_readers_page(target_notice uuid,before_time timestamptz default null,before_id uuid default null,page_size integer default 50)
returns table(profile_id uuid,reader_name text,read_at timestamptz,avatar_url text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not coalesce(classmate.can_read_notice(target_notice),false) then raise exception 'Notice access required' using errcode='42501'; end if;
  if (before_time is null)<>(before_id is null) then raise exception 'Both cursor values are required'; end if;
  return query select r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),r.read_at,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    from classmate.notice_reads r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
    where r.notice_id=target_notice and (before_time is null or r.read_at<before_time or (r.read_at=before_time and r.profile_id>before_id))
    order by r.read_at desc,r.profile_id limit least(greatest(page_size,1),50);
end $$;

-- Index-backed lateral limits avoid sorting every reader to obtain four avatars.
create or replace function classmate.notice_reader_previews(target_ids uuid[])
returns table(notice_id uuid,profile_id uuid,reader_name text,avatar_url text,read_at timestamptz)
language sql stable security definer set search_path='' as $$
  select n.id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member'),
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end,r.read_at
  from classmate.notices n cross join lateral (
    select profile_id,read_at from classmate.notice_reads where notice_id=n.id order by read_at desc,profile_id limit 4
  ) r join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
  where n.id=any(target_ids[1:100]) and classmate.can_read_notice(n.id);
$$;
create function classmate.notice_feed_details(target_ids uuid[]) returns jsonb
language sql stable security definer set search_path='' as $$
  select jsonb_build_object(
    'engagement',coalesce((select jsonb_agg(to_jsonb(e)) from classmate.notice_engagement(target_ids[1:100]) e),'[]'::jsonb),
    'authors',coalesce((select jsonb_agg(to_jsonb(a)) from classmate.notice_author_details(target_ids[1:100]) a),'[]'::jsonb),
    'reads',coalesce((select jsonb_agg(to_jsonb(r)) from classmate.notice_read_counts(target_ids[1:100]) r),'[]'::jsonb),
    'previews',coalesce((select jsonb_agg(to_jsonb(p)) from classmate.notice_reader_previews(target_ids[1:100]) p),'[]'::jsonb));
$$;
revoke all on function classmate.notice_readers_page(uuid,timestamptz,uuid,integer),classmate.notice_feed_details(uuid[]) from public,anon;
grant execute on function classmate.notice_readers_page(uuid,timestamptz,uuid,integer),classmate.notice_feed_details(uuid[]) to authenticated;

create table classmate.hosting_usage_snapshots (
  id bigint generated always as identity primary key, observed_at timestamptz not null default now(),
  plan text,compute_size text,database_limit_bytes bigint,egress_bytes bigint,egress_limit_bytes bigint,
  source text not null,details jsonb not null default '{}'::jsonb
);
alter table classmate.hosting_usage_snapshots enable row level security;
revoke all on classmate.hosting_usage_snapshots from public,anon,authenticated;
grant all on classmate.hosting_usage_snapshots to service_role;
grant usage,select on sequence classmate.hosting_usage_snapshots_id_seq to service_role;

create function classmate.system_health() returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  if not classmate.is_owner() or not classmate.is_active() then raise exception 'Owner required' using errcode='42501'; end if;
  select jsonb_build_object('observed_at',now(),'database_bytes',pg_database_size(current_database()),
    'pending_jobs',(select count(*) from classmate.notification_jobs where state in('pending','leased')),
    'unprepared_events',(select count(*) from classmate.notification_outbox where delivered_at is null and not jobs_prepared),
    'oldest_pending_notice_seconds',(select coalesce(extract(epoch from now()-min(created_at)),0) from classmate.notification_outbox where delivered_at is null and kind<>'app_update'),
    'accepted_sends',(select count(*) from classmate.notification_deliveries),
    'failed_jobs',(select count(*) from classmate.notification_jobs where state='failed'),
    'client_states',(select coalesce(jsonb_object_agg(state,total),'{}') from (select state,count(*) total from classmate.notification_client_receipts group by state) c),
    'largest_tables',(select coalesce(jsonb_agg(t),'[]') from (select relname as name,pg_total_relation_size(relid) as bytes,n_live_tup as estimated_rows from pg_stat_user_tables where schemaname='classmate' order by pg_total_relation_size(relid) desc limit 8) t),
    'failed_events',(select coalesce(jsonb_agg(f),'[]') from (select e.id,e.kind,e.created_at,count(*) as devices from classmate.notification_outbox e join classmate.notification_jobs j on j.event_id=e.id and j.state='failed' group by e.id order by e.created_at desc limit 20) f),
    'hosting',(select to_jsonb(h)-'id' from classmate.hosting_usage_snapshots h order by observed_at desc,id desc limit 1)) into result;
  return result;
end $$;
revoke all on function classmate.system_health() from public,anon;
grant execute on function classmate.system_health() to authenticated;
commit;
