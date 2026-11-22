-- 202611210002_fix_upcoming_assessments_server_bugs.sql
-- Fixes critical server-side bugs in Upcoming Assessments:
-- 1. Fixes teacher RLS permissions by aligning with classmate.can_post() and classmate.can_read_batch()
-- 2. Closes security hole for banned/unverified students in assessments_read
-- 3. Enables Supabase Realtime publication for classmate.assessments
-- 4. Sanitizes corrupted 'null' string values in notice_id and converts notice_id to uuid with foreign key cascade
-- 5. Enforces valid assessment types check

begin;

-- 1. Sanitize existing data
update classmate.assessments
set notice_id = null
where notice_id is not null and (notice_id = 'null' or trim(notice_id) = '');

-- Normalize type values
update classmate.assessments
set type = 'class_test'
where type in ('ct', 'CT', 'classtest', 'Class Test');

-- 2. Alter notice_id to UUID with proper Foreign Key to classmate.notices
alter table classmate.assessments
  alter column notice_id type uuid using (
    case 
      when notice_id is null or notice_id = '' or notice_id = 'null' then null 
      else notice_id::uuid 
    end
  );

-- Add foreign key constraint if not exists
do $$
begin
  if not exists (
    select 1 from information_schema.table_constraints 
    where constraint_name = 'assessments_notice_id_fkey' and table_name = 'assessments'
  ) then
    alter table classmate.assessments
      add constraint assessments_notice_id_fkey
      foreign key (notice_id) references classmate.notices(id) on delete set null;
  end if;
end $$;

-- 3. Add check constraint on assessment type to maintain clean schema
do $$
begin
  if not exists (
    select 1 from information_schema.table_constraints 
    where constraint_name = 'assessments_type_check' and table_name = 'assessments'
  ) then
    alter table classmate.assessments
      add constraint assessments_type_check
      check (type in ('class_test', 'assignment', 'quiz', 'lab_report', 'presentation'));
  end if;
end $$;

-- 4. Update Row-Level Security Policies with clean, battle-tested checks
drop policy if exists assessments_read on classmate.assessments;
create policy assessments_read on classmate.assessments
  for select to authenticated
  using (
    classmate.is_owner() 
    or classmate.can_read_batch(batch_id)
  );

drop policy if exists assessments_write on classmate.assessments;
create policy assessments_write on classmate.assessments
  for insert to authenticated
  with check (
    classmate.is_owner()
    or classmate.can_post(batch_id, course_id)
  );

drop policy if exists assessments_modify on classmate.assessments;
create policy assessments_modify on classmate.assessments
  for update to authenticated
  using (
    classmate.is_owner()
    or classmate.can_post(batch_id, course_id)
  );

drop policy if exists assessments_delete on classmate.assessments;
create policy assessments_delete on classmate.assessments
  for delete to authenticated
  using (
    classmate.is_owner()
    or classmate.can_post(batch_id, course_id)
  );

-- 5. Enable Real-Time Replication on classmate.assessments
alter table classmate.assessments replica identity full;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables 
    where pubname = 'supabase_realtime' and tablename = 'assessments' and schemaname = 'classmate'
  ) then
    alter publication supabase_realtime add table classmate.assessments;
  end if;
end $$;

commit;
