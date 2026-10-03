begin;
create table classmate.academic_calendar_publications (
  year integer primary key check(year between 2000 and 2200),
  title text not null,
  notes text not null,
  source_note text not null,
  updated_at timestamptz not null default now()
);
alter table classmate.academic_calendar_publications enable row level security;
create policy signed_in_calendar_publications on classmate.academic_calendar_publications for select to authenticated using(auth.uid() is not null);
grant select on classmate.academic_calendar_publications to authenticated;
revoke all on classmate.academic_calendar_publications from anon;
create table classmate.academic_calendar_events (
  id uuid primary key default gen_random_uuid(),
  title text not null check(length(trim(title)) between 1 and 160),
  start_date date not null,
  end_date date not null check(end_date >= start_date),
  scope text not null check(scope in ('university','classes','observance','working_day')),
  provisional boolean not null default false,
  source_key text unique,
  source_note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index academic_calendar_dates on classmate.academic_calendar_events(start_date,end_date);
alter table classmate.academic_calendar_events enable row level security;
create policy signed_in_calendar on classmate.academic_calendar_events for select to authenticated using (auth.uid() is not null);
grant select on classmate.academic_calendar_events to authenticated;
revoke all on classmate.academic_calendar_events from anon;
create function classmate.save_calendar_event(target_id uuid,target_title text,target_start date,target_end date,target_scope text)
returns classmate.academic_calendar_events language plpgsql security definer set search_path='' as $$
declare saved classmate.academic_calendar_events;
begin
  perform classmate.require_owner();
  if target_id is null then
    insert into classmate.academic_calendar_events(title,start_date,end_date,scope)
    values(trim(target_title),target_start,target_end,target_scope) returning * into saved;
  else
    update classmate.academic_calendar_events set title=trim(target_title),start_date=target_start,end_date=target_end,scope=target_scope,updated_at=now()
    where id=target_id returning * into saved;
    if not found then raise exception 'Calendar event no longer exists'; end if;
  end if;
  insert into classmate.audit_log(actor_id,action,target_id) values(auth.uid(),'save_calendar_event',saved.id);
  return saved;
end $$;
create function classmate.delete_calendar_event(target_id uuid) returns void language plpgsql security definer set search_path='' as $$
begin
  perform classmate.require_owner();
  delete from classmate.academic_calendar_events where id=target_id;
  insert into classmate.audit_log(actor_id,action,target_id) values(auth.uid(),'delete_calendar_event',target_id);
end $$;
revoke all on function classmate.save_calendar_event(uuid,text,date,date,text),classmate.delete_calendar_event(uuid) from public,anon;
grant execute on function classmate.save_calendar_event(uuid,text,date,date,text),classmate.delete_calendar_event(uuid) to authenticated;
notify pgrst,'reload schema';
commit;
