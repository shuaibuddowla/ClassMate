begin;

-- 1. Resilient Reply Threading: Allow replying to any comment or reply seamlessly
create or replace function classmate.save_notice_comment(
  target_notice uuid,
  target_body text,
  target_parent uuid default null,
  target_id uuid default null,
  target_request uuid default null
) returns classmate.notice_comments
language plpgsql security definer set search_path = '' as $$
declare
  saved classmate.notice_comments%rowtype;
  resolved_parent uuid := null;
begin
  if not classmate.is_active() or not classmate.can_read_notice(target_notice) then
    raise exception 'Notice access denied' using errcode = '42501';
  end if;

  if length(trim(target_body)) not between 1 and 2000 then
    raise exception 'Write a comment within 2,000 characters';
  end if;

  if target_parent is not null then
    -- Resolve to the root comment of the thread so 1-level nesting is strictly preserved without failing
    select coalesce(p.parent_id, p.id) into resolved_parent
    from classmate.notice_comments p
    where p.id = target_parent and p.notice_id = target_notice and p.deleted_at is null;

    if resolved_parent is null then
      raise exception 'Reply to an available comment in this notice';
    end if;
  end if;

  if target_id is null then
    insert into classmate.notice_comments(notice_id, author_id, parent_id, body, client_request)
    values (target_notice, auth.uid(), resolved_parent, trim(target_body), target_request)
    on conflict (author_id, client_request) where client_request is not null do nothing
    returning * into saved;

    if saved.id is null then
      select * into saved from classmate.notice_comments
      where author_id = auth.uid() and client_request = target_request;

      if saved.notice_id <> target_notice or saved.parent_id is distinct from resolved_parent then
        raise exception 'Comment request conflict';
      end if;
    end if;
  else
    update classmate.notice_comments set body = trim(target_body), updated_at = now()
    where id = target_id and notice_id = target_notice and author_id = auth.uid() and deleted_at is null
    returning * into saved;

    if not found then
      raise exception 'You can edit only your own available comment' using errcode = '42501';
    end if;
  end if;

  return saved;
end $$;

-- 2. Notice Image Attachments: Reconcile public.notice_attachments with classmate.notices
do $$
begin
  -- Drop existing foreign key constraints pointing to legacy tables if present
  if exists (
    select 1 from information_schema.table_constraints
    where constraint_name = 'notice_attachments_notice_id_fkey' and table_name = 'notice_attachments'
  ) then
    alter table public.notice_attachments drop constraint notice_attachments_notice_id_fkey;
  end if;

  if exists (
    select 1 from information_schema.table_constraints
    where constraint_name = 'notice_attachments_uploader_id_fkey' and table_name = 'notice_attachments'
  ) then
    alter table public.notice_attachments drop constraint notice_attachments_uploader_id_fkey;
  end if;

  -- Add constraints pointing to classmate schema
  alter table public.notice_attachments
    add constraint notice_attachments_notice_id_fkey
    foreign key (notice_id) references classmate.notices(id) on delete cascade;

  alter table public.notice_attachments
    add constraint notice_attachments_uploader_id_fkey
    foreign key (uploader_id) references classmate.profiles(id) on delete restrict;
exception
  when others then null;
end $$;

-- Update prepare_notice_image to check classmate.notices
create or replace function public.prepare_notice_image(
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
    select 1 from classmate.notices n
    where n.id = target_notice and (n.author_id = auth.uid() or classmate.is_owner())
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
    (attachment_id, target_notice, auth.uid(), object_key, safe_name, image_mime_type, image_size_bytes);

  return jsonb_build_object('id', attachment_id, 'storage_key', object_key);
end;
$$;

create or replace function public.discard_failed_notice_upload(target_notice uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from classmate.notices n
  where n.id = target_notice
    and (n.author_id = auth.uid() or classmate.is_owner())
    and n.published_at >= now() - interval '10 minutes';
end;
$$;

-- Ensure RLS policies on notice_attachments and storage bucket use classmate.can_read_notice
drop policy if exists "users read visible notice images" on public.notice_attachments;
create policy "users read visible notice images"
on public.notice_attachments for select to authenticated
using (classmate.can_read_notice(notice_id));

drop policy if exists "users download visible notice images" on storage.objects;
create policy "users download visible notice images"
on storage.objects for select to authenticated
using (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = name and classmate.can_read_notice(a.notice_id)
  )
);

revoke all on function public.prepare_notice_image(uuid, text, text, bigint) from public, anon;
grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated;

revoke all on function public.discard_failed_notice_upload(uuid) from public, anon;
grant execute on function public.discard_failed_notice_upload(uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
