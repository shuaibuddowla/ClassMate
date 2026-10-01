begin;

create table classmate.notice_reads (
  notice_id uuid not null references classmate.notices(id) on delete cascade,
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  read_at timestamptz not null default now(),
  primary key (notice_id, profile_id)
);
create index notice_reads_profile_idx on classmate.notice_reads(profile_id, read_at desc);
alter table classmate.notice_reads enable row level security;
create policy own_notice_reads on classmate.notice_reads for select to authenticated
  using (profile_id = (select auth.uid()) and classmate.can_read_notice(notice_id));
grant select on classmate.notice_reads to authenticated;

create function classmate.mark_notices_read(target_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not classmate.is_active() then
    raise exception 'Active account required' using errcode = '42501';
  end if;
  insert into classmate.notice_reads(notice_id, profile_id)
  select n.id, auth.uid() from classmate.notices n
  where n.id = any(target_ids) and classmate.can_read_notice(n.id)
  on conflict do nothing;
end;
$$;
revoke all on function classmate.mark_notices_read(uuid[]) from public, anon;
grant execute on function classmate.mark_notices_read(uuid[]) to authenticated;

create function classmate.notice_read_counts(target_ids uuid[])
returns table(notice_id uuid, read_count bigint, read_by_me boolean)
language sql stable security definer set search_path = '' as $$
  select n.id, (select count(*) from classmate.notice_reads r where r.notice_id = n.id),
    exists (select 1 from classmate.notice_reads own
      where own.notice_id = n.id and own.profile_id = (select auth.uid()))
  from classmate.notices n
  where n.id = any(target_ids) and classmate.can_read_notice(n.id);
$$;
revoke all on function classmate.notice_read_counts(uuid[]) from public, anon;
grant execute on function classmate.notice_read_counts(uuid[]) to authenticated;

create function classmate.notice_readers(target_notice uuid)
returns table(profile_id uuid, reader_name text, read_at timestamptz)
language plpgsql stable security definer set search_path = '' as $$
declare author uuid;
begin
  select n.author_id into author from classmate.notices n where n.id = target_notice;
  if author is null or not (author = auth.uid() or classmate.is_owner()) then
    raise exception 'Read receipts are available to the author and owner'
      using errcode = '42501';
  end if;
  return query select r.profile_id,
    coalesce(nullif(trim(p.full_name), ''), 'ClassMate member'), r.read_at
  from classmate.notice_reads r join classmate.profiles p on p.id = r.profile_id
  where r.notice_id = target_notice order by r.read_at desc;
end;
$$;
revoke all on function classmate.notice_readers(uuid) from public, anon;
grant execute on function classmate.notice_readers(uuid) to authenticated;

alter table classmate.notices add column resource_id uuid
  references classmate.file_metadata(id) on delete set null;
create unique index notices_resource_id_unique on classmate.notices(resource_id)
  where resource_id is not null;

-- Upload completion posts the resource notice. It replaces the older file-only
-- push so users receive one notification with a direct file link in the feed.
create or replace function classmate.enqueue_file() returns trigger language plpgsql
set search_path = '' as $$
begin
  return new;
end;
$$;
create function classmate.sync_resource_notice() returns trigger language plpgsql
security definer set search_path = '' as $$
declare resource_title text;
begin
  if old.status = 'pending' and new.status = 'active' then
    resource_title := left('Resource: ' || new.title, 200);
    insert into classmate.notices(department_id, batch_id, semester_course_id,
      author_id, title, body, resource_id)
    values (new.department_id, new.batch_id, new.semester_course_id,
      new.uploaded_by, resource_title,
      'A new learning resource has been shared. Open the file below.', new.id);
  elsif old.status in ('active', 'deleting') and new.status = 'archived' then
    delete from classmate.notification_outbox o
      using classmate.notices n
      where n.resource_id = new.id and o.kind = 'notice' and o.record_id = n.id;
    delete from classmate.notices where resource_id = new.id;
  end if;
  return new;
end;
$$;
create trigger sync_resource_notice after update of status on classmate.file_metadata
  for each row execute function classmate.sync_resource_notice();

alter table classmate.file_metadata drop constraint if exists file_metadata_status_check;
alter table classmate.file_metadata add constraint file_metadata_status_check
  check (status in ('pending', 'active', 'deleting', 'archived'));

commit;
