begin;

alter table public.notices
  add column if not exists resource_reference text,
  add column if not exists resource_title text,
  add column if not exists resource_subject text,
  add column if not exists resource_provider text;

create table if not exists public.notice_attachments (
  id uuid primary key default gen_random_uuid(),
  notice_id uuid not null references public.notices(id) on delete cascade,
  uploader_id uuid not null references public.profiles(id) on delete restrict,
  storage_key text not null unique,
  file_name text not null,
  mime_type text not null check (mime_type in ('image/jpeg', 'image/png', 'image/webp')),
  size_bytes bigint not null check (size_bytes between 1 and 10485760),
  created_at timestamptz not null default now()
);

alter table public.notice_attachments enable row level security;

create policy "users read visible notice images"
on public.notice_attachments for select to authenticated
using (public.can_read_notice(notice_id));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'notice-images', 'notice-images', false, 10485760,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy "users download visible notice images"
on storage.objects for select to authenticated
using (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = name and public.can_read_notice(a.notice_id)
  )
);

create policy "publishers upload their notice images"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'notice-images'
  and owner_id = public.current_firebase_uid()
  and exists (
    select 1 from public.notice_attachments a
    join public.notices n on n.id = a.notice_id
    where a.storage_key = name
      and a.uploader_id = public.current_profile_id()
      and n.author_id = public.current_profile_id()
      and n.deleted_at is null
  )
);

create policy "publishers delete their notice images"
on storage.objects for delete to authenticated
using (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    join public.notices n on n.id = a.notice_id
    where a.storage_key = name
      and (n.author_id = public.current_profile_id() or public.can_manage_notice(n.id))
  )
);

create or replace function public.update_notice(
  target_notice uuid,
  notice_title text,
  notice_body text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_active_user() then
    raise exception 'An active account is required' using errcode = '42501';
  end if;
  if char_length(btrim(notice_title)) not between 1 and 200
     or char_length(coalesce(notice_body, '')) > 5000 then
    raise exception 'The notice title or body is invalid' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.notices n
    where n.id = target_notice and n.deleted_at is null
      and (n.author_id = public.current_profile_id() or public.can_manage_notice(n.id))
  ) then
    raise exception 'You cannot edit this notice' using errcode = '42501';
  end if;

  update public.notices
  set title = btrim(notice_title), body = coalesce(notice_body, ''), updated_at = now()
  where id = target_notice;
  return jsonb_build_object('id', target_notice);
end;
$$;

revoke all on function public.update_notice(uuid, text, text) from public, anon;
grant execute on function public.update_notice(uuid, text, text) to authenticated;

create function public.prepare_notice_image(
  target_notice uuid,
  image_file_name text,
  image_mime_type text,
  image_size_bytes bigint
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  attachment_id uuid := gen_random_uuid();
  safe_name text;
  object_key text;
begin
  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp')
     or image_size_bytes not between 1 and 10485760 then
    raise exception 'Only JPG, PNG, or WebP images up to 10 MB are supported' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.notices n
    where n.id = target_notice and n.author_id = public.current_profile_id()
      and n.deleted_at is null
  ) then
    raise exception 'You cannot attach an image to this notice' using errcode = '42501';
  end if;
  if exists (select 1 from public.notice_attachments a where a.notice_id = target_notice) then
    raise exception 'This notice already has an image' using errcode = '23505';
  end if;

  safe_name := left(regexp_replace(coalesce(nullif(image_file_name, ''), 'image'), '[^A-Za-z0-9._-]', '_', 'g'), 160);
  object_key := target_notice::text || '/' || attachment_id::text || '/' || safe_name;
  insert into public.notice_attachments
    (id, notice_id, uploader_id, storage_key, file_name, mime_type, size_bytes)
  values
    (attachment_id, target_notice, public.current_profile_id(), object_key, safe_name, image_mime_type, image_size_bytes);
  return jsonb_build_object('id', attachment_id, 'storage_key', object_key);
end;
$$;

revoke all on function public.prepare_notice_image(uuid, text, text, bigint) from public, anon;
grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated;

create function public.discard_failed_notice_upload(target_notice uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from public.notices n
  where n.id = target_notice
    and n.author_id = public.current_profile_id()
    and n.created_at >= now() - interval '10 minutes';
end;
$$;

revoke all on function public.discard_failed_notice_upload(uuid) from public, anon;
grant execute on function public.discard_failed_notice_upload(uuid) to authenticated;

create function public.publish_resource_notice(
  target_batch_code text,
  notice_title text,
  notice_body text,
  target_resource_reference text,
  target_resource_title text,
  target_resource_subject text,
  target_resource_provider text default 'archive'
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  result jsonb;
  created_notice uuid;
begin
  if nullif(btrim(target_resource_reference), '') is null then
    raise exception 'A resource reference is required' using errcode = '22023';
  end if;
  result := public.publish_batch_notice(target_batch_code, notice_title, notice_body, 'normal');
  created_notice := (result ->> 'id')::uuid;
  update public.notices set
    resource_reference = btrim(target_resource_reference),
    resource_title = nullif(btrim(target_resource_title), ''),
    resource_subject = nullif(btrim(target_resource_subject), ''),
    resource_provider = coalesce(nullif(btrim(target_resource_provider), ''), 'archive')
  where id = created_notice;
  return result;
end;
$$;

revoke all on function public.publish_resource_notice(text, text, text, text, text, text, text) from public, anon;
grant execute on function public.publish_resource_notice(text, text, text, text, text, text, text) to authenticated;

drop function if exists public.list_visible_notices();

create function public.list_visible_notices(
  since_time timestamptz default null,
  before_time timestamptz default null,
  page_size integer default 40
)
returns table (
  id uuid,
  author_id uuid,
  author_name text,
  title text,
  body text,
  priority public.notice_priority,
  globally_pinned boolean,
  published_at timestamptz,
  created_at timestamptz,
  updated_at timestamptz,
  like_count bigint,
  is_liked boolean,
  is_personally_pinned boolean,
  is_cancellation boolean,
  subject text,
  attachment_storage_key text,
  attachment_file_name text,
  attachment_mime_type text,
  resource_reference text,
  resource_title text,
  resource_subject text,
  resource_provider text
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    n.id, n.author_id,
    coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1)),
    n.title, n.body, n.priority, n.globally_pinned, n.published_at,
    n.created_at, n.updated_at,
    (select count(*) from public.notice_likes nl where nl.notice_id = n.id),
    exists (select 1 from public.notice_likes nl where nl.notice_id = n.id and nl.profile_id = public.current_profile_id()),
    exists (select 1 from public.notice_pins np where np.notice_id = n.id and np.profile_id = public.current_profile_id()),
    cancellation.subject is not null,
    coalesce(cancellation.subject, n.resource_subject),
    image.storage_key, image.file_name, image.mime_type,
    n.resource_reference, n.resource_title, n.resource_subject, n.resource_provider
  from public.notices n
  join public.profiles p on p.id = n.author_id
  left join lateral (
    select c.name as subject
    from public.class_changes cc
    join public.routine_slots r on r.id = cc.routine_slot_id
    join public.course_offerings o on o.id = r.course_offering_id
    join public.courses c on c.id = o.course_id
    where cc.notice_id = n.id and cc.kind = 'cancelled' and cc.deleted_at is null
    limit 1
  ) cancellation on true
  left join lateral (
    select a.storage_key, a.file_name, a.mime_type
    from public.notice_attachments a where a.notice_id = n.id
    order by a.created_at limit 1
  ) image on true
  where public.is_active_user()
    and n.state = 'published' and n.deleted_at is null
    and public.can_read_notice(n.id)
    and (since_time is null or coalesce(n.published_at, n.created_at) >= since_time)
    and (before_time is null or coalesce(n.published_at, n.created_at) < before_time)
  order by n.globally_pinned desc, n.published_at desc nulls last, n.created_at desc
  limit least(greatest(page_size, 1), 100);
$$;

revoke all on function public.list_visible_notices(timestamptz, timestamptz, integer) from public, anon;
grant execute on function public.list_visible_notices(timestamptz, timestamptz, integer) to authenticated;

create function public.list_notice_engagement(target_notices uuid[])
returns table (
  notice_id uuid,
  like_count bigint,
  is_liked boolean,
  globally_pinned boolean,
  is_personally_pinned boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  select n.id,
    (select count(*) from public.notice_likes nl where nl.notice_id = n.id),
    exists (select 1 from public.notice_likes nl where nl.notice_id = n.id and nl.profile_id = public.current_profile_id()),
    n.globally_pinned,
    exists (select 1 from public.notice_pins np where np.notice_id = n.id and np.profile_id = public.current_profile_id())
  from public.notices n
  where n.id = any(target_notices)
    and n.deleted_at is null
    and public.can_read_notice(n.id);
$$;

revoke all on function public.list_notice_engagement(uuid[]) from public, anon;
grant execute on function public.list_notice_engagement(uuid[]) to authenticated;

-- Owner management is enforced in database functions as well as in the UI.
create or replace function public.delete_notice(target_notice uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare existing public.notices%rowtype;
begin
  select * into existing from public.notices where id = target_notice and deleted_at is null for update;
  if existing.id is null then raise exception 'Notice not found' using errcode = 'P0002'; end if;
  if existing.author_id <> public.current_profile_id() and not public.can_manage_notice(target_notice) then
    raise exception 'Not authorized to delete this notice' using errcode = '42501';
  end if;
  update public.notices set deleted_at = now(), updated_at = now() where id = target_notice;
end; $$;
revoke all on function public.delete_notice(uuid) from public;
grant execute on function public.delete_notice(uuid) to authenticated;

create or replace function public.archive_routine_slot(target_slot uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare existing public.routine_slots%rowtype;
begin
  select * into existing from public.routine_slots where id = target_slot and deleted_at is null for update;
  if existing.id is null then raise exception 'Period not found' using errcode = 'P0002'; end if;
  if existing.created_by <> public.current_profile_id()
    and not public.can_admin_academic_scope(null, null, null, existing.course_offering_id) then
    raise exception 'Not authorized to delete this period' using errcode = '42501';
  end if;
  update public.routine_slots set deleted_at = now(), deleted_by = public.current_profile_id()
  where id = target_slot;
end; $$;
revoke all on function public.archive_routine_slot(uuid) from public;
grant execute on function public.archive_routine_slot(uuid) to authenticated;

create or replace function public.archive_academic_resource(target_resource uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare existing public.resources%rowtype;
begin
  select * into existing from public.resources where id = target_resource and deleted_at is null for update;
  if existing.id is null then raise exception 'Resource not found' using errcode = 'P0002'; end if;
  if existing.uploader_id <> public.current_profile_id()
    and not public.can_manage_academic_scope(null, null, null, existing.course_offering_id) then
    raise exception 'Not authorized to delete this resource' using errcode = '42501';
  end if;
  update public.resources set state = 'archived', deleted_at = now(), deleted_by = public.current_profile_id()
  where id = target_resource;
end; $$;
revoke all on function public.archive_academic_resource(uuid) from public;
grant execute on function public.archive_academic_resource(uuid) to authenticated;

commit;
