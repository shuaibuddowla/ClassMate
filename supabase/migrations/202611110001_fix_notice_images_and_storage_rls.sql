-- 202611110001_fix_notice_images_and_storage_rls.sql
-- Fixes notice image and PDF uploads, storage.objects RLS policies,
-- prepare_notice_image and can_post public exposure for Edge Functions.

begin;

-- ============================================================================
-- 1. Expose public.can_post to ensure Edge Functions can call it in any schema
-- ============================================================================
create or replace function public.can_post(target_batch uuid, target_course uuid default null::uuid)
returns boolean
language sql
stable security definer
set search_path to ''
as $$
  select classmate.can_post(target_batch, target_course);
$$;

grant execute on function public.can_post(uuid, uuid) to anon, authenticated, service_role;
grant execute on function classmate.can_post(uuid, uuid) to anon, authenticated, service_role;

-- ============================================================================
-- 2. Update prepare_notice_image to support classmate.notices & admins
-- ============================================================================
create or replace function public.prepare_notice_image(
  target_notice uuid,
  image_file_name text,
  image_mime_type text,
  image_size_bytes bigint
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  attachment_id uuid := gen_random_uuid();
  safe_name text;
  object_key text;
  existing_count int;
  p_role text;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
     or image_size_bytes not between 1 and 10485760 then
    raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 10 MB are supported' using errcode = '22023';
  end if;

  select role into p_role from classmate.profiles where id = auth.uid();

  -- Verify user is the notice author, app owner, or admin
  if not exists (
    select 1 from classmate.notices n
    where n.id = target_notice
      and (n.author_id = auth.uid() or classmate.is_owner() or p_role = 'admin')
  ) and not exists (
    select 1 from public.notices n
    where n.id = target_notice
      and (n.author_id = auth.uid() or classmate.is_owner() or p_role = 'admin')
  ) then
    raise exception 'You cannot attach a file to this notice' using errcode = '42501';
  end if;

  select count(*) into existing_count from public.notice_attachments a where a.notice_id = target_notice;

  if image_mime_type = 'application/pdf' then
    if existing_count > 0 then
      raise exception 'A PDF notice can only have one document attachment' using errcode = '23505';
    end if;
  else
    if exists (select 1 from public.notice_attachments a where a.notice_id = target_notice and a.mime_type = 'application/pdf') then
      raise exception 'Cannot mix images with a PDF attachment' using errcode = '23505';
    end if;
    if existing_count >= 5 then
      raise exception 'Maximum 5 images allowed per notice' using errcode = '23505';
    end if;
  end if;

  safe_name := left(regexp_replace(coalesce(nullif(image_file_name, ''), 'attachment'), '[^A-Za-z0-9._-]', '_', 'g'), 160);
  object_key := target_notice::text || '/' || attachment_id::text || '/' || safe_name;

  insert into public.notice_attachments
    (id, notice_id, uploader_id, storage_key, file_name, mime_type, size_bytes)
  values
    (attachment_id, target_notice, auth.uid(), object_key, safe_name, image_mime_type, image_size_bytes);

  return jsonb_build_object('id', attachment_id, 'storage_key', object_key);
end;
$$;

grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated, service_role;

-- ============================================================================
-- 3. Update discard_failed_notice_upload
-- ============================================================================
create or replace function public.discard_failed_notice_upload(target_notice uuid)
returns void
language plpgsql
security definer
set search_path to ''
as $$
begin
  delete from public.notice_attachments where notice_id = target_notice and uploader_id = auth.uid();
  delete from classmate.notices n
  where n.id = target_notice
    and (n.author_id = auth.uid() or classmate.is_owner() or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin'))
    and n.published_at >= now() - interval '10 minutes';
end;
$$;

grant execute on function public.discard_failed_notice_upload(uuid) to authenticated, service_role;

-- ============================================================================
-- 4. Rewrite storage.objects RLS policies for notice-images bucket
-- ============================================================================
drop policy if exists "publishers upload their notice images" on storage.objects;
drop policy if exists "publishers delete their notice images" on storage.objects;
drop policy if exists "users download visible notice images" on storage.objects;
drop policy if exists "publishers update their notice images" on storage.objects;

-- Allow upload (insert)
create policy "publishers upload their notice images"
on storage.objects for insert
to authenticated
with check (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = objects.name
      and (
        a.uploader_id = auth.uid()
        or classmate.is_owner()
        or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
        or exists (select 1 from classmate.notices n where n.id = a.notice_id and n.author_id = auth.uid())
      )
  )
);

-- Allow update
create policy "publishers update their notice images"
on storage.objects for update
to authenticated
using (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = objects.name
      and (
        a.uploader_id = auth.uid()
        or classmate.is_owner()
        or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
        or exists (select 1 from classmate.notices n where n.id = a.notice_id and n.author_id = auth.uid())
      )
  )
)
with check (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = objects.name
      and (
        a.uploader_id = auth.uid()
        or classmate.is_owner()
        or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
        or exists (select 1 from classmate.notices n where n.id = a.notice_id and n.author_id = auth.uid())
      )
  )
);

-- Allow delete
create policy "publishers delete their notice images"
on storage.objects for delete
to authenticated
using (
  bucket_id = 'notice-images'
  and exists (
    select 1 from public.notice_attachments a
    where a.storage_key = objects.name
      and (
        a.uploader_id = auth.uid()
        or classmate.is_owner()
        or exists (select 1 from classmate.profiles where id = auth.uid() and role = 'admin')
        or exists (select 1 from classmate.notices n where n.id = a.notice_id and (n.author_id = auth.uid() or classmate.can_manage_batch(n.batch_id)))
      )
  )
);

-- Allow read (select)
create policy "users download visible notice images"
on storage.objects for select
to public
using (
  bucket_id = 'notice-images'
  and (
    -- Authenticated user checking notice read permissions
    exists (
      select 1 from public.notice_attachments a
      where a.storage_key = objects.name
        and (classmate.can_read_notice(a.notice_id) or public.can_read_notice(a.notice_id))
    )
    or
    -- Allow signed URL token access
    auth.role() = 'authenticated'
  )
);

commit;
