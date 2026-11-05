-- Enable PDF support for notice attachments in storage bucket and prepare_notice_image RPC

update storage.buckets
set allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp', 'application/pdf']
where id = 'notice-images';

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
  if image_mime_type not in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
     or image_size_bytes not between 1 and 10485760 then
    raise exception 'Only JPG, PNG, WebP images, or PDF documents up to 10 MB are supported' using errcode = '22023';
  end if;

  if not exists (
    select 1 from classmate.notices n
    where n.id = target_notice and (n.author_id = auth.uid() or classmate.is_owner())
  ) then
    raise exception 'You cannot attach a file to this notice' using errcode = '42501';
  end if;

  if exists (select 1 from public.notice_attachments a where a.notice_id = target_notice) then
    raise exception 'This notice already has an attachment' using errcode = '23505';
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

revoke all on function public.prepare_notice_image(uuid, text, text, bigint) from public, anon;
grant execute on function public.prepare_notice_image(uuid, text, text, bigint) to authenticated;
