begin;

-- 1. Add slug column to classmate.file_metadata if not exists
alter table classmate.file_metadata
  add column if not exists slug text;

-- 2. Create function to slugify text
create or replace function classmate.slugify(raw_text text)
returns text
language plpgsql
immutable
as $$
declare
  cleaned text;
begin
  if raw_text is null or trim(raw_text) = '' then
    return 'resource';
  end if;

  -- Lowercase and replace non-alphanumeric chars (preserving unicode letters if desired, or standard ascii slugs)
  cleaned := lower(trim(raw_text));
  -- Replace whitespace, underscores, slashes, periods, and symbols with hyphens
  cleaned := regexp_replace(cleaned, '[^a-z0-9]+', '-', 'g');
  -- Remove leading and trailing hyphens
  cleaned := regexp_replace(cleaned, '^-+|-+$', '', 'g');

  if cleaned = '' then
    return 'resource';
  end if;

  return cleaned;
end;
$$;

-- 3. Create function to generate unique slug with deduplication (-1, -2, ...)
create or replace function classmate.generate_unique_resource_slug(raw_title text, current_id uuid default null)
returns text
language plpgsql
as $$
declare
  base_slug text;
  candidate_slug text;
  counter integer := 1;
  collision_exists boolean;
begin
  base_slug := classmate.slugify(raw_title);
  candidate_slug := base_slug;

  loop
    select exists (
      select 1
      from classmate.file_metadata
      where slug = candidate_slug
        and (current_id is null or id <> current_id)
        and status = 'active'
    ) into collision_exists;

    exit when not collision_exists;

    candidate_slug := base_slug || '-' || counter;
    counter := counter + 1;
  end loop;

  return candidate_slug;
end;
$$;

-- 4. Trigger to automatically set slug on insert or title update
create or replace function classmate.set_resource_slug_trigger()
returns trigger
language plpgsql
as $$
begin
  if NEW.slug is null or trim(NEW.slug) = '' or (TG_OP = 'UPDATE' and NEW.title is distinct from OLD.title and (NEW.slug = OLD.slug or NEW.slug is null)) then
    NEW.slug := classmate.generate_unique_resource_slug(NEW.title, NEW.id);
  end if;
  return NEW;
end;
$$;

drop trigger if exists trg_set_resource_slug on classmate.file_metadata;
create trigger trg_set_resource_slug
before insert or update of title, slug on classmate.file_metadata
for each row
execute function classmate.set_resource_slug_trigger();

-- 5. Backfill existing file_metadata rows
do $$
declare
  rec record;
begin
  for rec in
    select id, title
    from classmate.file_metadata
    where slug is null or trim(slug) = ''
    order by created_at asc
  loop
    update classmate.file_metadata
    set slug = classmate.generate_unique_resource_slug(rec.title, rec.id)
    where id = rec.id;
  end loop;
end;
$$;

-- 6. Add unique index for active slugs
create unique index if not exists idx_file_metadata_slug_active
  on classmate.file_metadata(lower(slug))
  where status = 'active';

-- 7. Update classmate.get_public_resource to accept text (slug OR uuid)
create or replace function classmate.get_public_resource(target_identifier text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  res record;
  as_uuid uuid := null;
begin
  if target_identifier is null or trim(target_identifier) = '' then
    return null;
  end if;

  -- Test if target_identifier is a valid UUID
  if target_identifier ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    as_uuid := target_identifier::uuid;
  end if;

  select
    f.id,
    f.slug,
    f.title,
    f.file_type,
    f.size_bytes,
    f.mime_type,
    f.link_url,
    f.created_at,
    (f.link_url is not null and f.link_url <> '') as is_link,
    coalesce(sc.title_override, c.course_title) as course_title,
    coalesce(sc.code_override, c.course_code) as course_code,
    f.semester_course_id,
    b.batch_number
  into res
  from classmate.file_metadata f
  left join classmate.semester_courses sc on sc.id = f.semester_course_id
  left join classmate.courses c on c.id = sc.course_id
  left join classmate.batches b on b.id = f.batch_id
  where (
    (as_uuid is not null and f.id = as_uuid)
    or (lower(f.slug) = lower(trim(target_identifier)))
  ) and f.status = 'active'
  limit 1;

  if res.id is null then
    return null;
  end if;

  return jsonb_build_object(
    'id', res.id,
    'slug', res.slug,
    'title', res.title,
    'file_type', res.file_type,
    'size_bytes', res.size_bytes,
    'mime_type', res.mime_type,
    'link_url', res.link_url,
    'is_link', res.is_link,
    'course_title', res.course_title,
    'course_code', res.course_code,
    'semester_course_id', res.semester_course_id,
    'batch_number', res.batch_number,
    'created_at', res.created_at
  );
end;
$$;

-- Also support uuid parameter overload for compatibility
create or replace function classmate.get_public_resource(target_resource uuid)
returns jsonb
language sql
security definer
as $$
  select classmate.get_public_resource(target_resource::text);
$$;

-- 8. Public schema wrappers
create or replace function public.get_public_resource(target_identifier text)
returns jsonb
language sql
security definer
as $$
  select classmate.get_public_resource(target_identifier);
$$;

create or replace function public.get_public_resource(target_resource uuid)
returns jsonb
language sql
security definer
as $$
  select classmate.get_public_resource(target_resource::text);
$$;

-- 9. Update telegram broadcast trigger to send slug
create or replace function classmate.trigger_broadcast_resource_telegram()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_batch_num integer;
  v_course_title text;
  v_course_code text;
  v_uploader_name text;
  v_secret text;
  v_should_broadcast boolean := false;
  v_slug text;
begin
  if tg_op = 'INSERT' and new.status = 'active' then
    v_should_broadcast := true;
  elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'active' then
    v_should_broadcast := true;
  end if;

  if not v_should_broadcast then
    return new;
  end if;

  -- Strictly check if target batch is CSE 22
  select batch_number into v_batch_num from classmate.batches where id = new.batch_id;
  if v_batch_num is distinct from 22 then
    return new;
  end if;

  -- Get dispatcher secret from vault
  select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'classmate_dispatch_secret' limit 1;
  if v_secret is null then
    return new;
  end if;

  -- Resolve course title and code by joining with classmate.courses
  if new.semester_course_id is not null then
    select coalesce(sc.title_override, c.course_title), coalesce(sc.code_override, c.course_code)
    into v_course_title, v_course_code
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    where sc.id = new.semester_course_id limit 1;
  end if;

  -- Resolve uploader name
  if new.uploaded_by is not null then
    select coalesce(nullif(trim(p.full_name), ''), 'ClassMate Contributor') into v_uploader_name
    from classmate.profiles p
    where p.id = new.uploaded_by limit 1;
  end if;

  v_slug := coalesce(new.slug, new.id::text);

  -- Asynchronously post to Cloudflare Worker Telegram broadcast endpoint
  perform net.http_post(
    url := 'https://classmate-secure-api.shuaibuddowla-personal.workers.dev/internal/telegram-broadcast',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Dispatch-Secret', v_secret,
      'User-Agent', 'ClassMate-Database/1.0'
    ),
    body := jsonb_build_object(
      'resource_id', new.id,
      'slug', v_slug,
      'title', new.title,
      'course_title', v_course_title,
      'course_code', v_course_code,
      'uploader_name', v_uploader_name,
      'file_type', new.file_type,
      'batch_number', 22
    )
  );

  return new;
exception when others then
  return new;
end;
$$;

grant execute on function classmate.get_public_resource(text) to anon, authenticated, service_role;
grant execute on function classmate.get_public_resource(uuid) to anon, authenticated, service_role;
grant execute on function public.get_public_resource(text) to anon, authenticated, service_role;
grant execute on function public.get_public_resource(uuid) to anon, authenticated, service_role;

notify pgrst, 'reload schema';

commit;
