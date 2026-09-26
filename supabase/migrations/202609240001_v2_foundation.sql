begin;

create extension if not exists pgcrypto;
create extension if not exists citext;

create type public.profile_status as enum (
  'active',
  'pending_setup',
  'graduated',
  'suspended',
  'blocked'
);

create type public.app_role as enum ('student', 'cr', 'teacher', 'admin');
create type public.course_kind as enum ('theory', 'lab');
create type public.semester_state as enum ('draft', 'published', 'archived');
create type public.notice_priority as enum ('normal', 'important', 'urgent');
create type public.notice_state as enum ('draft', 'published', 'archived');
create type public.class_change_kind as enum (
  'cancelled',
  'room_changed',
  'rescheduled',
  'time_changed'
);
create type public.resource_state as enum ('active', 'replaced', 'archived');

create table public.universities (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  name text not null,
  email_domain citext not null unique,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

create table public.departments (
  id uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id) on delete cascade,
  code text not null,
  name text not null,
  email_prefix citext not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (university_id, code),
  unique (university_id, email_prefix)
);

create table public.semesters (
  id uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id) on delete cascade,
  ordinal smallint not null check (ordinal between 1 and 20),
  name text not null,
  created_at timestamptz not null default now(),
  unique (university_id, ordinal)
);

create table public.batches (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references public.departments(id) on delete cascade,
  cohort_code text not null,
  display_name text not null,
  admission_year smallint,
  is_archived boolean not null default false,
  created_at timestamptz not null default now(),
  unique (department_id, cohort_code)
);

create table public.sections (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references public.batches(id) on delete cascade,
  code text not null,
  name text not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (batch_id, code)
);

create table public.batch_semesters (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references public.batches(id) on delete cascade,
  semester_id uuid not null references public.semesters(id) on delete restrict,
  state public.semester_state not null default 'draft',
  starts_on date,
  ends_on date,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  unique (batch_id, semester_id),
  check (ends_on is null or starts_on is null or ends_on >= starts_on)
);

alter table public.batches
  add column active_batch_semester_id uuid
  references public.batch_semesters(id) on delete set null;

create table public.profiles (
  id uuid primary key default gen_random_uuid(),
  firebase_uid text not null unique,
  university_id uuid references public.universities(id) on delete restrict,
  email citext not null unique,
  display_name text not null default '',
  avatar_url text,
  status public.profile_status not null default 'pending_setup',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.student_profiles (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  department_id uuid references public.departments(id) on delete restrict,
  detected_batch_code text,
  assigned_batch_id uuid references public.batches(id) on delete restrict,
  section_id uuid references public.sections(id) on delete set null,
  roll_number text,
  assignment_overridden boolean not null default false,
  updated_at timestamptz not null default now()
);

create table public.staff_allowlist (
  id uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id) on delete cascade,
  email citext not null unique,
  role public.app_role not null check (role in ('teacher', 'admin')),
  department_id uuid references public.departments(id) on delete set null,
  is_active boolean not null default true,
  notes text,
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now()
);

create table public.courses (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references public.departments(id) on delete cascade,
  code text not null,
  name text not null,
  kind public.course_kind not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (department_id, code)
);

create table public.course_offerings (
  id uuid primary key default gen_random_uuid(),
  batch_semester_id uuid not null references public.batch_semesters(id) on delete cascade,
  course_id uuid not null references public.courses(id) on delete restrict,
  section_id uuid references public.sections(id) on delete cascade,
  created_at timestamptz not null default now()
);

create unique index course_offerings_unique_scope
  on public.course_offerings (
    batch_semester_id,
    course_id,
    coalesce(section_id, '00000000-0000-0000-0000-000000000000'::uuid)
  );

create table public.role_grants (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  role public.app_role not null check (role in ('cr', 'teacher', 'admin')),
  department_id uuid references public.departments(id) on delete cascade,
  batch_id uuid references public.batches(id) on delete cascade,
  section_id uuid references public.sections(id) on delete cascade,
  course_offering_id uuid references public.course_offerings(id) on delete cascade,
  starts_at timestamptz not null default now(),
  expires_at timestamptz,
  revoked_at timestamptz,
  granted_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  check (expires_at is null or expires_at > starts_at),
  check (
    role <> 'cr'
    or (batch_id is not null and course_offering_id is null)
  ),
  check (
    role <> 'teacher'
    or course_offering_id is not null
  )
);

create index role_grants_active_lookup
  on public.role_grants (profile_id, role, expires_at)
  where revoked_at is null;

create table public.routine_slots (
  id uuid primary key default gen_random_uuid(),
  course_offering_id uuid not null references public.course_offerings(id) on delete cascade,
  weekday smallint not null check (weekday between 0 and 6),
  starts_at time not null,
  ends_at time not null,
  room text,
  class_kind text not null default 'class',
  created_by uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  deleted_by uuid references public.profiles(id) on delete set null,
  check (ends_at > starts_at)
);

create table public.notices (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete restrict,
  title text not null check (char_length(title) between 1 and 200),
  body text not null default '',
  priority public.notice_priority not null default 'normal',
  state public.notice_state not null default 'draft',
  globally_pinned boolean not null default false,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  deleted_by uuid references public.profiles(id) on delete set null
);

create table public.notice_targets (
  id uuid primary key default gen_random_uuid(),
  notice_id uuid not null references public.notices(id) on delete cascade,
  university_id uuid references public.universities(id) on delete cascade,
  department_id uuid references public.departments(id) on delete cascade,
  batch_id uuid references public.batches(id) on delete cascade,
  section_id uuid references public.sections(id) on delete cascade,
  course_offering_id uuid references public.course_offerings(id) on delete cascade,
  created_at timestamptz not null default now(),
  check (num_nonnulls(university_id, department_id, batch_id, section_id, course_offering_id) = 1)
);

create unique index notice_targets_unique_scope
  on public.notice_targets (
    notice_id,
    coalesce(university_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(department_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(batch_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(section_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(course_offering_id, '00000000-0000-0000-0000-000000000000'::uuid)
  );

create table public.class_changes (
  id uuid primary key default gen_random_uuid(),
  routine_slot_id uuid not null references public.routine_slots(id) on delete cascade,
  notice_id uuid references public.notices(id) on delete set null,
  effective_date date not null,
  kind public.class_change_kind not null,
  previous_room text,
  new_room text,
  previous_starts_at time,
  previous_ends_at time,
  new_starts_at time,
  new_ends_at time,
  reason text,
  created_by uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  deleted_by uuid references public.profiles(id) on delete set null
);

create table public.bus_schedules (
  id uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id) on delete cascade,
  route_name text not null,
  departure_time time not null,
  origin text not null,
  destination text not null,
  weekdays smallint[] not null default '{0,1,2,3,4,5,6}',
  notes text,
  created_by uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  deleted_by uuid references public.profiles(id) on delete set null,
  check (weekdays <@ array[0,1,2,3,4,5,6]::smallint[])
);

create table public.resources (
  id uuid primary key default gen_random_uuid(),
  course_offering_id uuid not null references public.course_offerings(id) on delete cascade,
  uploader_id uuid not null references public.profiles(id) on delete restrict,
  logical_key text not null,
  version_number integer not null default 1 check (version_number > 0),
  replaces_resource_id uuid references public.resources(id) on delete set null,
  title text not null,
  description text not null default '',
  category text not null,
  exam_type text,
  file_name text not null,
  mime_type text not null,
  size_bytes bigint not null default 0 check (size_bytes >= 0),
  storage_provider text not null,
  storage_key text not null,
  state public.resource_state not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  deleted_by uuid references public.profiles(id) on delete set null,
  unique (course_offering_id, logical_key, version_number)
);

create table public.notice_resources (
  notice_id uuid not null references public.notices(id) on delete cascade,
  resource_id uuid not null references public.resources(id) on delete cascade,
  primary key (notice_id, resource_id)
);

create table public.notice_likes (
  notice_id uuid not null references public.notices(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (notice_id, profile_id)
);

create table public.notice_pins (
  notice_id uuid not null references public.notices(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (notice_id, profile_id)
);

create table public.notice_reminders (
  notice_id uuid not null references public.notices(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  remind_at timestamptz not null,
  created_at timestamptz not null default now(),
  primary key (notice_id, profile_id, remind_at)
);

create table public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  token text not null unique,
  platform text not null default 'android' check (platform = 'android'),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table public.course_notification_preferences (
  profile_id uuid not null references public.profiles(id) on delete cascade,
  course_offering_id uuid not null references public.course_offerings(id) on delete cascade,
  enabled boolean not null default true,
  updated_at timestamptz not null default now(),
  primary key (profile_id, course_offering_id)
);

create table public.audit_log (
  id bigint generated always as identity primary key,
  actor_id uuid references public.profiles(id) on delete set null,
  action text not null,
  entity_table text not null,
  entity_id uuid,
  old_data jsonb,
  new_data jsonb,
  created_at timestamptz not null default now()
);

create or replace function public.current_firebase_uid()
returns text
language sql
stable
set search_path = ''
as $$
  select nullif(auth.jwt() ->> 'sub', '');
$$;

create or replace function public.current_profile_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select p.id
  from public.profiles p
  where p.firebase_uid = public.current_firebase_uid()
  limit 1;
$$;

create or replace function public.is_active_user()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.profiles p
    where p.id = public.current_profile_id()
      and p.status in ('active', 'graduated')
  );
$$;

create or replace function public.has_active_role(
  requested_role public.app_role,
  requested_department uuid default null,
  requested_batch uuid default null,
  requested_section uuid default null,
  requested_offering uuid default null
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.role_grants g
    where g.profile_id = public.current_profile_id()
      and g.role = requested_role
      and g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
      and (g.department_id is null or g.department_id = requested_department)
      and (g.batch_id is null or g.batch_id = requested_batch)
      and (g.section_id is null or g.section_id = requested_section)
      and (g.course_offering_id is null or g.course_offering_id = requested_offering)
  );
$$;

create or replace function public.has_publisher_role()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_user() and exists (
    select 1
    from public.role_grants g
    where g.profile_id = public.current_profile_id()
      and g.role in ('cr', 'teacher', 'admin')
      and g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
  );
$$;

create or replace function public.can_access_batch(target_batch uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_user() and (
    exists (
      select 1 from public.student_profiles s
      where s.profile_id = public.current_profile_id() and s.assigned_batch_id = target_batch
    )
    or exists (
      select 1 from public.role_grants g
      where g.profile_id = public.current_profile_id()
        and g.revoked_at is null
        and g.starts_at <= now()
        and (g.expires_at is null or g.expires_at > now())
        and (
          g.batch_id = target_batch
          or (g.department_id is not null and exists (
            select 1 from public.batches b
            where b.id = target_batch and b.department_id = g.department_id
          ))
          or (g.course_offering_id is not null and exists (
            select 1
            from public.course_offerings o
            join public.batch_semesters bs on bs.id = o.batch_semester_id
            where o.id = g.course_offering_id and bs.batch_id = target_batch
          ))
          or (g.department_id is null and g.batch_id is null and g.section_id is null and g.course_offering_id is null and g.role = 'admin')
        )
    )
  );
$$;

create or replace function public.can_access_department(target_department uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_user() and (
    exists (
      select 1 from public.student_profiles s
      where s.profile_id = public.current_profile_id() and s.department_id = target_department
    )
    or exists (
      select 1
      from public.role_grants g
      where g.profile_id = public.current_profile_id()
        and g.revoked_at is null
        and g.starts_at <= now()
        and (g.expires_at is null or g.expires_at > now())
        and (
          g.department_id = target_department
          or (g.batch_id is not null and exists (
            select 1 from public.batches b
            where b.id = g.batch_id and b.department_id = target_department
          ))
          or (g.course_offering_id is not null and exists (
            select 1
            from public.course_offerings o
            join public.batch_semesters bs on bs.id = o.batch_semester_id
            join public.batches b on b.id = bs.batch_id
            where o.id = g.course_offering_id and b.department_id = target_department
          ))
          or (g.department_id is null and g.batch_id is null and g.section_id is null and g.course_offering_id is null and g.role = 'admin')
        )
    )
  );
$$;

create or replace function public.can_access_section(target_section uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_user() and exists (
    select 1
    from public.sections s
    join public.batches b on b.id = s.batch_id
    where s.id = target_section
      and (
        exists (
          select 1 from public.student_profiles sp
          where sp.profile_id = public.current_profile_id()
            and sp.assigned_batch_id = s.batch_id
            and sp.section_id = s.id
        )
        or exists (
          select 1 from public.role_grants g
          where g.profile_id = public.current_profile_id()
            and g.revoked_at is null
            and g.starts_at <= now()
            and (g.expires_at is null or g.expires_at > now())
            and (
              g.section_id = target_section
              or g.batch_id = s.batch_id
              or g.department_id = b.department_id
              or (g.course_offering_id is not null and exists (
                select 1
                from public.course_offerings o
                join public.batch_semesters bs on bs.id = o.batch_semester_id
                where o.id = g.course_offering_id
                  and bs.batch_id = s.batch_id
                  and (o.section_id is null or o.section_id = s.id)
              ))
              or (g.department_id is null and g.batch_id is null and g.section_id is null and g.course_offering_id is null and g.role = 'admin')
            )
        )
      )
  );
$$;

create or replace function public.can_access_offering(target_offering uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.course_offerings o
    join public.batch_semesters bs on bs.id = o.batch_semester_id
    where o.id = target_offering
      and (
        (o.section_id is null and public.can_access_batch(bs.batch_id))
        or (o.section_id is not null and public.can_access_section(o.section_id))
        or public.has_active_role('teacher', null, null, null, o.id)
      )
  );
$$;

create or replace function public.can_manage_academic_scope(
  target_department uuid default null,
  target_batch uuid default null,
  target_section uuid default null,
  target_offering uuid default null
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  with resolved_scope as (
    select
      coalesce(target_department, b.department_id) as department_id,
      coalesce(target_batch, bs.batch_id, s.batch_id) as batch_id,
      coalesce(target_section, o.section_id) as section_id,
      target_offering as offering_id
    from (values (1)) as seed(value)
    left join public.course_offerings o on o.id = target_offering
    left join public.batch_semesters bs on bs.id = o.batch_semester_id
    left join public.sections s on s.id = target_section
    left join public.batches b on b.id = coalesce(target_batch, bs.batch_id, s.batch_id)
  )
  select public.is_active_user() and exists (
    select 1
    from resolved_scope scope
    join public.role_grants g on g.profile_id = public.current_profile_id()
    where g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
      and g.role in ('cr', 'teacher', 'admin')
      and (g.department_id is null or g.department_id = scope.department_id)
      and (g.batch_id is null or g.batch_id = scope.batch_id)
      and (g.section_id is null or g.section_id = scope.section_id)
      and (g.course_offering_id is null or g.course_offering_id = scope.offering_id)
      and (g.role <> 'teacher' or g.course_offering_id = scope.offering_id)
      and (g.role <> 'cr' or g.batch_id = scope.batch_id)
  );
$$;

create or replace function public.can_admin_academic_scope(
  target_department uuid default null,
  target_batch uuid default null,
  target_section uuid default null,
  target_offering uuid default null
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  with resolved_scope as (
    select
      coalesce(target_department, b.department_id) as department_id,
      coalesce(target_batch, bs.batch_id, s.batch_id) as batch_id,
      coalesce(target_section, o.section_id) as section_id,
      target_offering as offering_id
    from (values (1)) as seed(value)
    left join public.course_offerings o on o.id = target_offering
    left join public.batch_semesters bs on bs.id = o.batch_semester_id
    left join public.sections s on s.id = target_section
    left join public.batches b on b.id = coalesce(target_batch, bs.batch_id, s.batch_id)
  )
  select public.is_active_user() and exists (
    select 1
    from resolved_scope scope
    join public.role_grants g on g.profile_id = public.current_profile_id()
    where g.role = 'admin'
      and g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
      and (g.department_id is null or g.department_id = scope.department_id)
      and (g.batch_id is null or g.batch_id = scope.batch_id)
      and (g.section_id is null or g.section_id = scope.section_id)
      and (g.course_offering_id is null or g.course_offering_id = scope.offering_id)
  );
$$;

create or replace function public.can_read_notice(target_notice uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_user() and exists (
    select 1
    from public.notice_targets t
    where t.notice_id = target_notice
      and (
        (t.university_id is not null and exists (
          select 1 from public.profiles p
          where p.id = public.current_profile_id() and p.university_id = t.university_id
        ))
        or (t.department_id is not null and public.can_access_department(t.department_id))
        or (t.batch_id is not null and public.can_access_batch(t.batch_id))
        or (t.section_id is not null and public.can_access_section(t.section_id))
        or (t.course_offering_id is not null and public.can_access_offering(t.course_offering_id))
      )
  );
$$;

create or replace function public.can_manage_notice_target(
  target_university uuid,
  target_department uuid,
  target_batch uuid,
  target_section uuid,
  target_offering uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when target_offering is not null then exists (
      select 1
      from public.course_offerings o
      join public.batch_semesters bs on bs.id = o.batch_semester_id
      join public.batches b on b.id = bs.batch_id
      where o.id = target_offering
        and public.can_manage_academic_scope(b.department_id, bs.batch_id, o.section_id, o.id)
    )
    when target_section is not null then exists (
      select 1 from public.sections s
      join public.batches b on b.id = s.batch_id
      where s.id = target_section
        and public.can_manage_academic_scope(b.department_id, b.id, s.id, null)
    )
    when target_batch is not null then exists (
      select 1 from public.batches b
      where b.id = target_batch
        and public.can_manage_academic_scope(b.department_id, b.id, null, null)
    )
    when target_department is not null then
      public.has_active_role('admin', target_department, null, null, null)
    when target_university is not null then
      public.has_active_role('admin', null, null, null, null)
    else false
  end;
$$;

create or replace function public.can_manage_notice(target_notice uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.notice_targets t
    left join public.batches b on b.id = t.batch_id
    left join public.sections s on s.id = t.section_id
    left join public.course_offerings o on o.id = t.course_offering_id
    left join public.batch_semesters bs on bs.id = o.batch_semester_id
    left join public.batches ob on ob.id = bs.batch_id
    where t.notice_id = target_notice
      and public.can_manage_notice_target(
        t.university_id,
        coalesce(t.department_id, b.department_id, ob.department_id),
        coalesce(t.batch_id, s.batch_id, bs.batch_id),
        t.section_id,
        t.course_offering_id
      )
  );
$$;

create or replace function public.bootstrap_firebase_profile()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  token_firebase_uid text := public.current_firebase_uid();
  normalized_email citext := lower(auth.jwt() ->> 'email');
  normalized_domain citext;
  identity_parts text[];
  matched_university public.universities%rowtype;
  matched_department public.departments%rowtype;
  matched_batch public.batches%rowtype;
  allowed_staff public.staff_allowlist%rowtype;
  profile_state public.profile_status := 'blocked';
  resolved_profile_id uuid;
begin
  if token_firebase_uid is null then
    raise exception 'A verified Firebase identity is required';
  end if;

  if normalized_email is null
     or coalesce((auth.jwt() ->> 'email_verified')::boolean, false) is not true then
    raise exception 'A verified email address is required';
  end if;

  if coalesce(auth.jwt() -> 'firebase' ->> 'sign_in_provider', '') <> 'google.com' then
    raise exception 'Google authentication is required';
  end if;

  normalized_domain := split_part(normalized_email::text, '@', 2)::citext;
  select * into matched_university
  from public.universities u
  where u.email_domain = normalized_domain and u.is_active
  limit 1;

  if matched_university.id is null then
    raise exception 'University email domain is not authorized';
  end if;

  identity_parts := regexp_match(
    normalized_email::text,
    '^([a-z]+)([0-9]{2})([0-9]{3})@' || replace(matched_university.email_domain::text, '.', '[.]') || '$'
  );

  select * into allowed_staff
  from public.staff_allowlist a
  where a.email = normalized_email and a.is_active
  limit 1;

  if allowed_staff.id is not null then
    profile_state := 'active';
  elsif identity_parts is not null then
    select * into matched_department
    from public.departments d
    where d.university_id = matched_university.id
      and d.email_prefix = identity_parts[1]::citext
      and d.is_active
    limit 1;

    if matched_department.id is not null then
      select * into matched_batch
      from public.batches b
      where b.department_id = matched_department.id
        and b.cohort_code = identity_parts[2]
        and not b.is_archived
      limit 1;
    end if;

    profile_state := case
      when matched_batch.id is not null then 'active'::public.profile_status
      else 'pending_setup'::public.profile_status
    end;
  end if;

  insert into public.profiles (
    firebase_uid, university_id, email, display_name, avatar_url, status
  ) values (
    token_firebase_uid,
    matched_university.id,
    normalized_email,
    coalesce(auth.jwt() ->> 'name', normalized_email::text),
    auth.jwt() ->> 'picture',
    profile_state
  )
  on conflict (firebase_uid) do update set
    email = excluded.email,
    display_name = excluded.display_name,
    avatar_url = excluded.avatar_url,
    updated_at = now()
  returning id into resolved_profile_id;

  if allowed_staff.id is not null and allowed_staff.role = 'admin' then
    insert into public.role_grants (
      profile_id, role, department_id
    )
    select resolved_profile_id, allowed_staff.role, allowed_staff.department_id
    where not exists (
      select 1
      from public.role_grants g
      where g.profile_id = resolved_profile_id
        and g.role = allowed_staff.role
        and g.department_id is not distinct from allowed_staff.department_id
        and g.revoked_at is null
    );
  elsif identity_parts is not null then
    insert into public.student_profiles (
      profile_id,
      department_id,
      detected_batch_code,
      assigned_batch_id,
      roll_number
    ) values (
      resolved_profile_id,
      matched_department.id,
      identity_parts[2],
      matched_batch.id,
      identity_parts[3]
    )
    on conflict (profile_id) do nothing;
  end if;

  return resolved_profile_id;
end;
$$;

revoke all on function public.bootstrap_firebase_profile() from public;
revoke all on function public.bootstrap_firebase_profile() from anon;
grant execute on function public.bootstrap_firebase_profile() to authenticated;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger profiles_set_updated_at
  before update on public.profiles
  for each row execute procedure public.set_updated_at();
create trigger student_profiles_set_updated_at
  before update on public.student_profiles
  for each row execute procedure public.set_updated_at();
create trigger routine_slots_set_updated_at
  before update on public.routine_slots
  for each row execute procedure public.set_updated_at();
create trigger notices_set_updated_at
  before update on public.notices
  for each row execute procedure public.set_updated_at();
create trigger bus_schedules_set_updated_at
  before update on public.bus_schedules
  for each row execute procedure public.set_updated_at();
create trigger resources_set_updated_at
  before update on public.resources
  for each row execute procedure public.set_updated_at();

alter table public.universities enable row level security;
alter table public.departments enable row level security;
alter table public.semesters enable row level security;
alter table public.batches enable row level security;
alter table public.sections enable row level security;
alter table public.batch_semesters enable row level security;
alter table public.profiles enable row level security;
alter table public.student_profiles enable row level security;
alter table public.staff_allowlist enable row level security;
alter table public.courses enable row level security;
alter table public.course_offerings enable row level security;
alter table public.role_grants enable row level security;
alter table public.routine_slots enable row level security;
alter table public.notices enable row level security;
alter table public.notice_targets enable row level security;
alter table public.class_changes enable row level security;
alter table public.bus_schedules enable row level security;
alter table public.resources enable row level security;
alter table public.notice_resources enable row level security;
alter table public.notice_likes enable row level security;
alter table public.notice_pins enable row level security;
alter table public.notice_reminders enable row level security;
alter table public.device_tokens enable row level security;
alter table public.course_notification_preferences enable row level security;
alter table public.audit_log enable row level security;

create policy "authenticated users read universities"
  on public.universities for select to authenticated
  using (
    public.is_active_user()
    and exists (
      select 1 from public.profiles p
      where p.id = public.current_profile_id() and p.university_id = id
    )
  );
create policy "authenticated users read departments"
  on public.departments for select to authenticated
  using (
    public.is_active_user()
    and exists (
      select 1 from public.profiles p
      where p.id = public.current_profile_id() and p.university_id = university_id
    )
  );
create policy "authenticated users read semesters"
  on public.semesters for select to authenticated
  using (
    public.is_active_user()
    and exists (
      select 1 from public.profiles p
      where p.id = public.current_profile_id() and p.university_id = university_id
    )
  );
create policy "users read accessible batches"
  on public.batches for select to authenticated
  using (public.can_access_batch(id));
create policy "users read accessible sections"
  on public.sections for select to authenticated
  using (public.can_access_batch(batch_id));
create policy "users read accessible batch semesters"
  on public.batch_semesters for select to authenticated
  using (public.can_access_batch(batch_id));

create policy "users read own profile"
  on public.profiles for select to authenticated
  using (id = public.current_profile_id());
create policy "users update safe own profile fields"
  on public.profiles for update to authenticated
  using (id = public.current_profile_id())
  with check (id = public.current_profile_id());
revoke update on table public.profiles from authenticated;
grant update (display_name, avatar_url) on table public.profiles to authenticated;
create policy "users read own student profile"
  on public.student_profiles for select to authenticated
  using (profile_id = public.current_profile_id());

create policy "users read active courses"
  on public.courses for select to authenticated
  using (
    public.is_active_user()
    and is_active
    and exists (
      select 1
      from public.departments d
      join public.profiles p on p.university_id = d.university_id
      where d.id = department_id and p.id = public.current_profile_id()
    )
  );
create policy "users read accessible offerings"
  on public.course_offerings for select to authenticated
  using (public.can_access_offering(id));
create policy "users read own grants"
  on public.role_grants for select to authenticated
  using (profile_id = public.current_profile_id());

create policy "users read accessible routine slots"
  on public.routine_slots for select to authenticated
  using (deleted_at is null and public.can_access_offering(course_offering_id));
create policy "authorized users create routine slots"
  on public.routine_slots for insert to authenticated
  with check (
    created_by = public.current_profile_id()
    and public.can_admin_academic_scope(null, null, null, course_offering_id)
  );
create policy "authorized users update routine slots"
  on public.routine_slots for update to authenticated
  using (public.can_admin_academic_scope(null, null, null, course_offering_id))
  with check (public.can_admin_academic_scope(null, null, null, course_offering_id));

create policy "users read targeted published notices"
  on public.notices for select to authenticated
  using (
    (state = 'published' and deleted_at is null and public.can_read_notice(id))
    or author_id = public.current_profile_id()
    or public.can_manage_notice(id)
  );
create policy "authorized users create notices"
  on public.notices for insert to authenticated
  with check (author_id = public.current_profile_id() and public.has_publisher_role());
create policy "authorized users update notices"
  on public.notices for update to authenticated
  using (public.can_manage_notice(id))
  with check (public.can_manage_notice(id));
create policy "users read accessible notice targets"
  on public.notice_targets for select to authenticated
  using (public.can_read_notice(notice_id) or public.can_manage_notice(notice_id));
create policy "authors create authorized notice targets"
  on public.notice_targets for insert to authenticated
  with check (
    exists (select 1 from public.notices n where n.id = notice_id and n.author_id = public.current_profile_id())
    and public.can_manage_notice_target(university_id, department_id, batch_id, section_id, course_offering_id)
  );
create policy "managers delete notice targets"
  on public.notice_targets for delete to authenticated
  using (public.can_manage_notice(notice_id));

create policy "users read accessible class changes"
  on public.class_changes for select to authenticated
  using (
    deleted_at is null
    and exists (
      select 1 from public.routine_slots r
      where r.id = routine_slot_id and public.can_access_offering(r.course_offering_id)
    )
  );
create policy "authorized users create class changes"
  on public.class_changes for insert to authenticated
  with check (
    created_by = public.current_profile_id()
    and exists (
      select 1 from public.routine_slots r
      where r.id = routine_slot_id
        and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
    )
  );
create policy "authorized users update class changes"
  on public.class_changes for update to authenticated
  using (
    exists (
      select 1 from public.routine_slots r
      where r.id = routine_slot_id
        and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
    )
  );

create policy "university users read bus schedules"
  on public.bus_schedules for select to authenticated
  using (
    deleted_at is null
    and exists (
      select 1 from public.profiles p
      where p.id = public.current_profile_id() and p.university_id = university_id
    )
  );
create policy "admins create bus schedules"
  on public.bus_schedules for insert to authenticated
  with check (
    created_by = public.current_profile_id()
    and public.has_active_role('admin', null, null, null, null)
  );
create policy "admins update bus schedules"
  on public.bus_schedules for update to authenticated
  using (public.has_active_role('admin', null, null, null, null))
  with check (public.has_active_role('admin', null, null, null, null));
create policy "admins delete bus schedules"
  on public.bus_schedules for delete to authenticated
  using (public.has_active_role('admin', null, null, null, null));

create policy "users read accessible resources"
  on public.resources for select to authenticated
  using (deleted_at is null and public.can_access_offering(course_offering_id));
create policy "authorized users create resources"
  on public.resources for insert to authenticated
  with check (
    uploader_id = public.current_profile_id()
    and public.can_manage_academic_scope(null, null, null, course_offering_id)
  );
create policy "authorized users update resources"
  on public.resources for update to authenticated
  using (public.can_manage_academic_scope(null, null, null, course_offering_id))
  with check (public.can_manage_academic_scope(null, null, null, course_offering_id));
create policy "users read visible notice resource links"
  on public.notice_resources for select to authenticated
  using (public.can_read_notice(notice_id) and exists (
    select 1 from public.resources r
    where r.id = resource_id and r.deleted_at is null and public.can_access_offering(r.course_offering_id)
  ));
create policy "notice managers link resources"
  on public.notice_resources for insert to authenticated
  with check (
    public.can_manage_notice(notice_id)
    and exists (
      select 1 from public.resources r
      where r.id = resource_id
        and public.can_manage_academic_scope(null, null, null, r.course_offering_id)
    )
  );
create policy "notice managers unlink resources"
  on public.notice_resources for delete to authenticated
  using (public.can_manage_notice(notice_id));

create policy "users read likes for visible notices"
  on public.notice_likes for select to authenticated
  using (public.can_read_notice(notice_id));
create policy "users manage own notice likes"
  on public.notice_likes for all to authenticated
  using (profile_id = public.current_profile_id())
  with check (profile_id = public.current_profile_id() and public.can_read_notice(notice_id));
create policy "users manage own notice pins"
  on public.notice_pins for all to authenticated
  using (profile_id = public.current_profile_id())
  with check (profile_id = public.current_profile_id() and public.can_read_notice(notice_id));
create policy "users manage own notice reminders"
  on public.notice_reminders for all to authenticated
  using (profile_id = public.current_profile_id())
  with check (profile_id = public.current_profile_id() and public.can_read_notice(notice_id));
create policy "users manage own device tokens"
  on public.device_tokens for all to authenticated
  using (profile_id = public.current_profile_id())
  with check (profile_id = public.current_profile_id());
create policy "users manage own notification preferences"
  on public.course_notification_preferences for all to authenticated
  using (profile_id = public.current_profile_id())
  with check (profile_id = public.current_profile_id() and public.can_access_offering(course_offering_id));

revoke all on public.staff_allowlist from anon, authenticated;
revoke all on public.audit_log from anon, authenticated;
revoke update on public.profiles from authenticated;
grant update (display_name, avatar_url) on public.profiles to authenticated;

grant execute on function public.is_active_user() to authenticated;
grant execute on function public.has_active_role(public.app_role, uuid, uuid, uuid, uuid) to authenticated;
grant execute on function public.has_publisher_role() to authenticated;
grant execute on function public.can_access_batch(uuid) to authenticated;
grant execute on function public.can_access_department(uuid) to authenticated;
grant execute on function public.can_access_section(uuid) to authenticated;
grant execute on function public.can_access_offering(uuid) to authenticated;
grant execute on function public.can_manage_academic_scope(uuid, uuid, uuid, uuid) to authenticated;
grant execute on function public.can_admin_academic_scope(uuid, uuid, uuid, uuid) to authenticated;
grant execute on function public.can_read_notice(uuid) to authenticated;
grant execute on function public.can_manage_notice(uuid) to authenticated;
grant execute on function public.can_manage_notice_target(uuid, uuid, uuid, uuid, uuid) to authenticated;

commit;
