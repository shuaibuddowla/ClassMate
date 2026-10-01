-- Parallel Supabase Auth architecture. The existing Firebase-backed public V2
-- tables are intentionally untouched; migrate data only after identity mapping.
begin;

create schema if not exists classmate;
grant usage on schema classmate to authenticated;
grant usage on schema classmate to supabase_auth_admin;
create extension if not exists pgcrypto;

create type classmate.user_role as enum ('student', 'teacher', 'admin');
create type classmate.profile_source as enum ('auto', 'manual');
create type classmate.verification_status as enum ('active', 'pending', 'rejected');
create type classmate.semester_status as enum ('not_started', 'active', 'completed');

create table classmate.departments (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) between 2 and 160),
  code text not null unique check (code ~ '^[a-z][a-z0-9_]{1,19}$'),
  email_prefix text unique check (email_prefix is null or email_prefix ~ '^[a-z]+$'),
  session_offset smallint check (session_offset between 0 and 99),
  is_active boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((email_prefix is null) = (session_offset is null))
);

create table classmate.batches (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  batch_number smallint not null check (batch_number between 0 and 99),
  academic_session smallint not null check (academic_session between 0 and 99),
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (department_id, batch_number),
  unique (id, department_id)
);
create function classmate.validate_batch_formula() returns trigger language plpgsql
set search_path = '' as $$
declare offset_value smallint;
begin
  select session_offset into offset_value from classmate.departments where id = new.department_id;
  if offset_value is not null and new.batch_number <> new.academic_session - offset_value then
    raise exception 'Batch number must equal academic session minus department offset';
  end if;
  return new;
end;
$$;
create trigger batch_formula before insert or update on classmate.batches
  for each row execute function classmate.validate_batch_formula();
create function classmate.validate_department_rule() returns trigger language plpgsql
set search_path = '' as $$
begin
  if new.session_offset is not null and exists (
    select 1 from classmate.batches b where b.department_id = new.id
      and b.batch_number <> b.academic_session - new.session_offset) then
    raise exception 'New offset conflicts with existing batch data';
  end if;
  return new;
end;
$$;
create trigger department_rule before update of session_offset on classmate.departments
  for each row execute function classmate.validate_department_rule();

create table classmate.semesters (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references classmate.batches(id) on delete restrict,
  semester_number smallint not null check (semester_number between 1 and 8),
  status classmate.semester_status not null default 'not_started',
  published_at timestamptz,
  created_at timestamptz not null default now(),
  unique (batch_id, semester_number),
  unique (id, batch_id)
);
create unique index one_active_semester_per_batch on classmate.semesters(batch_id)
  where status = 'active';

create table classmate.courses (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  course_code text not null check (length(trim(course_code)) between 2 and 30),
  course_title text not null check (length(trim(course_title)) between 2 and 200),
  credit numeric(4,2) check (credit > 0 and credit <= 30),
  course_type text not null check (course_type in ('theory', 'lab', 'other')),
  created_at timestamptz not null default now(),
  unique (department_id, course_code),
  unique (id, department_id)
);

create table classmate.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text not null unique,
  full_name text not null default '',
  role classmate.user_role not null,
  department_id uuid references classmate.departments(id) on delete restrict,
  batch_id uuid,
  student_id text,
  academic_session smallint check (academic_session between 0 and 99),
  profile_source classmate.profile_source,
  verification_status classmate.verification_status not null default 'pending',
  rejection_reason text,
  is_cr boolean not null default false,
  cr_batch_id uuid,
  cr_valid_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (batch_id, department_id) references classmate.batches(id, department_id),
  foreign key (cr_batch_id, department_id) references classmate.batches(id, department_id),
  check (email = lower(email) and email like '%@mbstu.ac.bd'),
  check (role = 'student' or (batch_id is null and student_id is null and not is_cr and cr_batch_id is null)),
  check (not is_cr or (role = 'student' and verification_status = 'active' and cr_batch_id = batch_id)),
  check (verification_status <> 'rejected' or rejection_reason is not null)
);
create unique index student_id_per_department on classmate.profiles(department_id, student_id)
  where student_id is not null and verification_status = 'active';
create index profiles_pending_idx on classmate.profiles(department_id, created_at)
  where verification_status = 'pending' and role = 'student';

create table classmate.app_owners (
  email text primary key check (email = lower(email) and email like '%@mbstu.ac.bd'),
  active boolean not null default true,
  created_at timestamptz not null default now()
);
create unique index one_active_owner on classmate.app_owners ((true)) where active;
create table classmate.teacher_allowlist (
  id uuid primary key default gen_random_uuid(),
  email text not null check (email = lower(email) and email like '%@mbstu.ac.bd'),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  created_by uuid references classmate.profiles(id) on delete set null,
  unique (email, department_id)
);

create table classmate.semester_courses (
  id uuid primary key default gen_random_uuid(),
  semester_id uuid not null references classmate.semesters(id) on delete restrict,
  course_id uuid not null references classmate.courses(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (semester_id, course_id)
);
create table classmate.teacher_course_assignments (
  id uuid primary key default gen_random_uuid(),
  teacher_id uuid not null references classmate.profiles(id) on delete restrict,
  semester_course_id uuid not null references classmate.semester_courses(id) on delete restrict,
  active boolean not null default true,
  assigned_by uuid not null references classmate.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (teacher_id, semester_course_id)
);
create table classmate.routine_slots (
  id uuid primary key default gen_random_uuid(),
  semester_course_id uuid not null references classmate.semester_courses(id) on delete restrict,
  day_of_week smallint not null check (day_of_week between 0 and 6),
  start_time time not null,
  end_time time not null,
  room text,
  type text not null default 'class',
  created_at timestamptz not null default now(),
  check (end_time > start_time)
);

create table classmate.notices (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  batch_id uuid not null,
  semester_course_id uuid references classmate.semester_courses(id) on delete restrict,
  author_id uuid not null references classmate.profiles(id) on delete restrict,
  title text not null check (length(trim(title)) between 1 and 200),
  body text not null default '',
  published_at timestamptz not null default now(),
  foreign key (batch_id, department_id) references classmate.batches(id, department_id)
);
create index notices_batch_feed on classmate.notices(batch_id, published_at desc);

create table classmate.class_changes (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  batch_id uuid not null,
  semester_course_id uuid not null references classmate.semester_courses(id),
  author_id uuid not null references classmate.profiles(id),
  kind text not null check (kind in ('cancelled', 'rescheduled', 'room_changed', 'time_changed')),
  effective_date date not null,
  details text not null default '',
  created_at timestamptz not null default now(),
  foreign key (batch_id, department_id) references classmate.batches(id, department_id)
);

create table classmate.file_metadata (
  id uuid primary key default gen_random_uuid(),
  department_id uuid not null references classmate.departments(id) on delete restrict,
  batch_id uuid not null,
  semester_course_id uuid references classmate.semester_courses(id) on delete restrict,
  uploaded_by uuid not null references classmate.profiles(id) on delete restrict,
  title text not null,
  description text,
  file_type text not null,
  r2_object_key text not null unique,
  mime_type text not null,
  size_bytes bigint not null check (size_bytes >= 0),
  status text not null default 'pending' check (status in ('pending', 'active', 'archived')),
  created_at timestamptz not null default now(),
  foreign key (batch_id, department_id) references classmate.batches(id, department_id)
);

create table classmate.bus_schedules (
  id uuid primary key default gen_random_uuid(),
  route_name text not null,
  departure_time time not null,
  origin text not null,
  destination text not null,
  weekdays smallint[] not null default '{0,1,2,3,4,5,6}',
  notes text,
  active boolean not null default true,
  created_by uuid not null references classmate.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (weekdays <@ array[0,1,2,3,4,5,6]::smallint[])
);

create table classmate.device_tokens (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references classmate.profiles(id) on delete cascade,
  fcm_token text not null unique,
  updated_at timestamptz not null default now()
);
create table classmate.notification_outbox (
  id uuid primary key default gen_random_uuid(),
  kind text not null,
  record_id uuid not null,
  department_id uuid not null references classmate.departments(id),
  batch_id uuid not null references classmate.batches(id),
  semester_course_id uuid references classmate.semester_courses(id),
  routing_label text not null,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  attempts integer not null default 0,
  last_error text,
  unique (kind, record_id)
);
create table classmate.audit_log (
  id bigint generated always as identity primary key,
  actor_id uuid references classmate.profiles(id) on delete set null,
  action text not null,
  target_id uuid,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create function classmate.is_owner() returns boolean language sql stable security definer
set search_path = '' as $$
  select exists (
    select 1 from classmate.app_owners o join auth.users u on lower(u.email) = o.email
    where u.id = (select auth.uid()) and u.email_confirmed_at is not null and o.active
  );
$$;
create function classmate.is_active() returns boolean language sql stable security definer
set search_path = '' as $$
  select exists (select 1 from classmate.profiles p where p.id = (select auth.uid())
    and p.verification_status = 'active'
    and (p.role = 'student' or (p.role = 'admin' and classmate.is_owner())
      or (p.role = 'teacher' and exists (select 1 from classmate.teacher_allowlist a
        where a.email = p.email and a.active))));
$$;
create function classmate.can_read_batch(target_batch uuid) returns boolean language sql stable security definer
set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id = (select auth.uid())
      and p.verification_status = 'active'
      and ((p.role = 'student' and p.batch_id = target_batch)
        or (p.role = 'teacher' and exists (
          select 1 from classmate.teacher_course_assignments a
          join classmate.semester_courses sc on sc.id = a.semester_course_id
          join classmate.semesters s on s.id = sc.semester_id
          join classmate.batches b on b.id = s.batch_id
          join classmate.teacher_allowlist t on t.email = p.email
            and t.department_id = b.department_id and t.active
          where a.teacher_id = p.id and a.active and s.batch_id = target_batch)))
  );
$$;
create function classmate.can_manage_course(target_semester_course uuid) returns boolean language sql stable security definer
set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.teacher_course_assignments a
    join classmate.profiles p on p.id = a.teacher_id
    where a.teacher_id = (select auth.uid()) and a.semester_course_id = target_semester_course
      and a.active and p.role = 'teacher' and p.verification_status = 'active'
  );
$$;
create function classmate.can_post(target_batch uuid, target_course uuid default null)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id = (select auth.uid())
      and p.verification_status = 'active' and (
        (target_course is null and p.role = 'student' and p.is_cr
          and p.cr_batch_id = target_batch
          and (p.cr_valid_until is null or p.cr_valid_until > now()))
        or (target_course is not null and classmate.can_manage_course(target_course)
          and exists (select 1 from classmate.semester_courses sc
            join classmate.semesters s on s.id = sc.semester_id
            where sc.id = target_course and s.batch_id = target_batch
              and s.status = 'active'))
      )
  );
$$;

create function classmate.validate_scope() returns trigger language plpgsql
set search_path = '' as $$
declare d uuid; b uuid;
begin
  if tg_table_name = 'semester_courses' then
    select x.department_id into d from classmate.semesters s
      join classmate.batches x on x.id = s.batch_id where s.id = new.semester_id;
    if not exists (select 1 from classmate.courses c where c.id = new.course_id and c.department_id = d) then
      raise exception 'Course and semester department differ';
    end if;
  elsif tg_table_name = 'teacher_course_assignments' then
    select x.department_id into d from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id
      join classmate.batches x on x.id = s.batch_id where sc.id = new.semester_course_id;
    if not exists (select 1 from classmate.profiles p join classmate.teacher_allowlist a
      on a.email = p.email and a.department_id = d and a.active
      where p.id = new.teacher_id and p.role = 'teacher' and p.verification_status = 'active') then
      raise exception 'Teacher is not allowed for this department';
    end if;
  elsif tg_table_name in ('notices', 'file_metadata', 'class_changes')
    and new.semester_course_id is not null then
    select s.batch_id into b from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id where sc.id = new.semester_course_id;
    if b is distinct from new.batch_id then raise exception 'Course does not belong to batch'; end if;
  end if;
  return new;
end;
$$;
create trigger validate_semester_course before insert or update on classmate.semester_courses
  for each row execute function classmate.validate_scope();
create trigger validate_teacher_assignment before insert or update on classmate.teacher_course_assignments
  for each row execute function classmate.validate_scope();
create trigger validate_notice_scope before insert or update on classmate.notices
  for each row execute function classmate.validate_scope();
create trigger validate_class_change_scope before insert or update on classmate.class_changes
  for each row execute function classmate.validate_scope();
create trigger validate_file_scope before insert or update on classmate.file_metadata
  for each row execute function classmate.validate_scope();

create function classmate.make_semester_slots() returns trigger language plpgsql
set search_path = '' as $$
begin
  insert into classmate.semesters(batch_id, semester_number)
  select new.id, n from generate_series(1, 8) n;
  return new;
end;
$$;
create trigger batch_semester_slots after insert on classmate.batches
  for each row execute function classmate.make_semester_slots();

create function classmate.routing_label(target_batch uuid, target_course uuid default null)
returns text language plpgsql stable security definer set search_path = '' as $$
declare label text;
begin
  select 'dept_' || d.code || '_batch_' || b.batch_number into label
  from classmate.batches b join classmate.departments d on d.id = b.department_id
  where b.id = target_batch;
  if label is null then raise exception 'Batch not found'; end if;
  if target_course is not null then
    select label || '_course_' || regexp_replace(lower(c.course_code), '[^a-z0-9]', '_', 'g') into label
    from classmate.semester_courses sc join classmate.courses c on c.id = sc.course_id
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = target_course and s.batch_id = target_batch;
    if label is null then raise exception 'Course scope mismatch'; end if;
  end if;
  return label;
end;
$$;
create function classmate.enqueue_notice() returns trigger language plpgsql
set search_path = '' as $$
begin
  insert into classmate.notification_outbox(kind, record_id, department_id, batch_id,
    semester_course_id, routing_label)
  values ('notice', new.id, new.department_id, new.batch_id, new.semester_course_id,
    classmate.routing_label(new.batch_id, new.semester_course_id));
  return new;
end;
$$;
create trigger notice_outbox after insert on classmate.notices
  for each row execute function classmate.enqueue_notice();
create function classmate.enqueue_class_change() returns trigger language plpgsql
set search_path = '' as $$
begin
  insert into classmate.notification_outbox(kind, record_id, department_id,
    batch_id, semester_course_id, routing_label)
  values ('class_change', new.id, new.department_id, new.batch_id,
    new.semester_course_id,
    classmate.routing_label(new.batch_id, new.semester_course_id));
  return new;
end;
$$;
create trigger class_change_outbox after insert on classmate.class_changes
  for each row execute function classmate.enqueue_class_change();

create function classmate.enqueue_file() returns trigger language plpgsql
set search_path = '' as $$
begin
  if old.status <> 'active' and new.status = 'active' then
    insert into classmate.notification_outbox(kind, record_id, department_id, batch_id,
      semester_course_id, routing_label)
    values ('file', new.id, new.department_id, new.batch_id, new.semester_course_id,
      classmate.routing_label(new.batch_id, new.semester_course_id));
  end if;
  return new;
end;
$$;
create trigger file_outbox after update of status on classmate.file_metadata
  for each row execute function classmate.enqueue_file();

insert into classmate.departments(name, code, email_prefix, session_offset, is_active)
values ('Computer Science and Engineering', 'cse', 'ce', 3, true)
on conflict (code) do nothing;

-- No direct mutation of authority tables by clients. RPCs below own writes.
do $$ declare t text; begin
  foreach t in array array['departments','batches','semesters','courses','profiles',
    'app_owners','teacher_allowlist','semester_courses','teacher_course_assignments',
    'routine_slots','notices','class_changes','file_metadata','bus_schedules','device_tokens',
    'notification_outbox','audit_log'] loop
    execute format('alter table classmate.%I enable row level security', t);
  end loop;
end $$;
grant select on all tables in schema classmate to authenticated;
grant insert, update, delete on classmate.device_tokens to authenticated;
create policy departments_onboarding on classmate.departments for select to authenticated
  using (is_active or classmate.is_owner());
create policy own_profile on classmate.profiles for select to authenticated
  using (id = (select auth.uid()) or classmate.is_owner());
create policy active_batches on classmate.batches for select to authenticated
  using (classmate.can_read_batch(id) or classmate.is_owner());
create policy active_semesters on classmate.semesters for select to authenticated
  using (classmate.can_read_batch(batch_id) and (status in ('active', 'completed') or classmate.is_owner())
    or classmate.is_owner());
create policy active_courses on classmate.courses for select to authenticated
  using (classmate.is_owner() or (classmate.is_active() and exists (
    select 1 from classmate.semester_courses sc join classmate.semesters s on s.id = sc.semester_id
    where sc.course_id = classmate.courses.id and s.status in ('active', 'completed')
      and classmate.can_read_batch(s.batch_id))));
create policy active_semester_courses on classmate.semester_courses for select to authenticated
  using (classmate.is_owner() or exists (select 1 from classmate.semesters s
    where s.id = classmate.semester_courses.semester_id and s.status in ('active', 'completed')
      and classmate.can_read_batch(s.batch_id)));
create policy active_routine on classmate.routine_slots for select to authenticated
  using (classmate.is_owner() or exists (select 1 from classmate.semester_courses sc
    join classmate.semesters s on s.id = sc.semester_id
    where sc.id = classmate.routine_slots.semester_course_id and s.status in ('active', 'completed')
      and classmate.can_read_batch(s.batch_id)));
create policy batch_notices on classmate.notices for select to authenticated
  using (classmate.can_read_batch(batch_id) and
    (semester_course_id is null or classmate.can_manage_course(semester_course_id)
      or exists (select 1 from classmate.profiles p where p.id = (select auth.uid())
        and p.role = 'student' and p.batch_id = classmate.notices.batch_id)));
create policy batch_class_changes on classmate.class_changes for select to authenticated
  using (classmate.can_read_batch(batch_id) and
    (classmate.can_manage_course(semester_course_id) or exists
      (select 1 from classmate.profiles p where p.id = (select auth.uid())
        and p.role = 'student' and p.batch_id = classmate.class_changes.batch_id)));
create policy batch_files on classmate.file_metadata for select to authenticated
  using (status = 'active' and classmate.can_read_batch(batch_id)
    and (semester_course_id is null or exists (
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id = sc.semester_id
      where sc.id = classmate.file_metadata.semester_course_id and s.status in ('active', 'completed')))
    and (semester_course_id is null or classmate.can_manage_course(semester_course_id)
      or exists (select 1 from classmate.profiles p where p.id = (select auth.uid())
        and p.role = 'student' and p.batch_id = classmate.file_metadata.batch_id)));
create policy active_bus_schedules on classmate.bus_schedules for select to authenticated
  using ((active and classmate.is_active()) or classmate.is_owner());
create policy own_device_tokens on classmate.device_tokens for all to authenticated
  using (profile_id = (select auth.uid()) and classmate.is_active())
  with check (profile_id = (select auth.uid()) and classmate.is_active());
create policy owner_allowlist on classmate.teacher_allowlist for select to authenticated using (classmate.is_owner());
create policy own_assignments on classmate.teacher_course_assignments for select to authenticated
  using (teacher_id = (select auth.uid()) or classmate.is_owner());
create policy owner_audit on classmate.audit_log for select to authenticated using (classmate.is_owner());

revoke all on all functions in schema classmate from public, anon, authenticated;
grant usage on type classmate.user_role, classmate.profile_source,
  classmate.verification_status, classmate.semester_status to authenticated;
grant execute on function classmate.is_owner() to authenticated;
grant execute on function classmate.is_active() to authenticated;
grant execute on function classmate.can_read_batch(uuid) to authenticated;
grant execute on function classmate.can_manage_course(uuid) to authenticated;
grant execute on function classmate.can_post(uuid,uuid) to authenticated;
commit;
