-- 202611210001_upcoming_assessments.sql
-- Schema migration for Upcoming Assessments timeline, detail sheets, and smart timetable integration
-- Fully isolated by batch_id with row-level security.

begin;

create table if not exists classmate.assessments (
  id text primary key,
  batch_id uuid not null references classmate.batches(id) on delete cascade,
  course_id uuid references classmate.semester_courses(id) on delete set null,
  course_code text not null default '',
  course_title text not null default '',
  type text not null default 'ct',
  title text not null,
  deadline_date date not null,
  deadline_time text not null default '',
  room text not null default '',
  syllabus text not null default '',
  notice_id text,
  posted_by text not null default '',
  created_at bigint not null default (extract(epoch from now()) * 1000)::bigint
);

-- Index for high-performance timeline queries isolated by batch
create index if not exists idx_assessments_batch_deadline 
  on classmate.assessments (batch_id, deadline_date, deadline_time);

-- Enable Row Level Security
alter table classmate.assessments enable row level security;

-- Grant standard permissions to authenticated clients
grant select, insert, update, delete on classmate.assessments to authenticated;

-- RLS Policies for Strict Batch Isolation:
drop policy if exists assessments_read on classmate.assessments;
create policy assessments_read on classmate.assessments
  for select to authenticated
  using (
    classmate.is_owner() 
    or classmate.can_read_batch(batch_id)
    or exists (
      select 1 from classmate.profiles p 
      where p.id = (select auth.uid()) 
        and p.batch_id = classmate.assessments.batch_id
    )
  );

drop policy if exists assessments_write on classmate.assessments;
create policy assessments_write on classmate.assessments
  for insert to authenticated
  with check (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      where p.id = (select auth.uid())
        and p.verification_status = 'active'
        and (
          p.role = 'admin'
          or (p.role = 'teacher' and classmate.can_read_batch(batch_id))
          or (p.role = 'student' and p.is_cr and p.batch_id = classmate.assessments.batch_id)
        )
    )
  );

drop policy if exists assessments_modify on classmate.assessments;
create policy assessments_modify on classmate.assessments
  for update to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      where p.id = (select auth.uid())
        and p.verification_status = 'active'
        and (
          p.role = 'admin'
          or (p.role = 'teacher' and classmate.can_read_batch(batch_id))
          or (p.role = 'student' and p.is_cr and p.batch_id = classmate.assessments.batch_id)
        )
    )
  );

drop policy if exists assessments_delete on classmate.assessments;
create policy assessments_delete on classmate.assessments
  for delete to authenticated
  using (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles p
      where p.id = (select auth.uid())
        and p.verification_status = 'active'
        and (
          p.role = 'admin'
          or (p.role = 'teacher' and classmate.can_read_batch(batch_id))
          or (p.role = 'student' and p.is_cr and p.batch_id = classmate.assessments.batch_id)
        )
    )
  );

commit;
