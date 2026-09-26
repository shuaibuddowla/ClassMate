begin;

-- Private academic files. The database row is created first with a generated
-- resource ID, then the object is uploaded to:
--   <course_offering_id>/<resource_id>/<safe-file-name>
insert into storage.buckets (
  id, name, public, file_size_limit, allowed_mime_types
)
values (
  'academic-resources',
  'academic-resources',
  false,
  52428800,
  array[
    'application/pdf',
    'image/jpeg',
    'image/png',
    'image/webp',
    'text/plain',
    'text/csv',
    'application/zip',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  ]
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy "users download accessible academic resources"
on storage.objects for select to authenticated
using (
  bucket_id = 'academic-resources'
  and exists (
    select 1
    from public.resources r
    where r.storage_provider = 'supabase'
      and r.storage_key = name
      and r.deleted_at is null
      and public.can_access_offering(r.course_offering_id)
  )
);

create policy "publishers upload managed academic resources"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'academic-resources'
  and owner_id = public.current_firebase_uid()
  and exists (
    select 1
    from public.resources r
    where r.id::text = (storage.foldername(name))[2]
      and r.course_offering_id::text = (storage.foldername(name))[1]
      and r.storage_provider = 'supabase'
      and r.storage_key = name
      and r.uploader_id = public.current_profile_id()
      and r.deleted_at is null
      and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
  )
);

create policy "publishers update managed academic resources"
on storage.objects for update to authenticated
using (
  bucket_id = 'academic-resources'
  and exists (
    select 1
    from public.resources r
    where r.storage_provider = 'supabase'
      and r.storage_key = name
      and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
  )
)
with check (
  bucket_id = 'academic-resources'
  and exists (
    select 1
    from public.resources r
    where r.storage_provider = 'supabase'
      and r.storage_key = name
      and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
  )
);

create policy "publishers delete managed academic resources"
on storage.objects for delete to authenticated
using (
  bucket_id = 'academic-resources'
  and exists (
    select 1
    from public.resources r
    where r.storage_provider = 'supabase'
      and r.storage_key = name
      and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
  )
);

commit;
