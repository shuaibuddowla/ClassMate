-- Migration: Allow CR to manage and publish running semester for their batch,
-- support target_initial_semester in create_batch, and permit CRs to read draft semesters.

-- 1. Update RLS policy on classmate.semesters so batch CR can see draft semesters
drop policy if exists active_semesters on classmate.semesters;
create policy active_semesters on classmate.semesters for select to authenticated
  using (
    classmate.is_owner()
    or (
      classmate.can_read_batch(batch_id)
      and (
        status in ('active', 'completed')
        or classmate.can_manage_batch(batch_id)
      )
    )
  );

-- 2. Update publish_semester to permit both Admin and the batch's active CR
create or replace function classmate.publish_semester(target_semester uuid)
returns classmate.semesters
language plpgsql
security definer
set search_path = ''
as $$
declare
  s classmate.semesters%rowtype;
begin
  select * into s from classmate.semesters where id = target_semester;
  if s.id is null then raise exception 'Semester not found'; end if;

  if not (classmate.is_owner() or classmate.can_manage_batch(s.batch_id)) then
    raise exception 'Permission denied: Only Admin or the active CR can publish semesters' using errcode = '42501';
  end if;

  perform 1 from classmate.batches where id = s.batch_id for update;
  select * into s from classmate.semesters where id = target_semester for update;

  if s.status = 'active' then return s; end if;

  update classmate.semesters set status = 'completed'
  where batch_id = s.batch_id and status = 'active' and id <> s.id;

  update classmate.semesters set status = 'active', published_at = coalesce(s.published_at, now())
  where id = s.id returning * into s;

  insert into classmate.notification_outbox(kind, record_id, department_id, batch_id, routing_label)
  select 'semester', s.id, b.department_id, b.id, classmate.routing_label(b.id)
  from classmate.batches b where b.id = s.batch_id
  on conflict (kind, record_id) do nothing;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'publish_semester', s.id);

  return s;
end;
$$;

revoke all on function classmate.publish_semester(uuid) from public, anon;
grant execute on function classmate.publish_semester(uuid) to authenticated;

-- 3. Update create_batch to support optional initial running semester
drop function if exists classmate.create_batch(uuid, smallint, smallint);
drop function if exists classmate.create_batch(uuid, smallint, smallint, smallint);

create or replace function classmate.create_batch(
  target_department uuid,
  target_batch_number smallint,
  target_session smallint,
  target_initial_semester smallint default null
) returns classmate.batches
language plpgsql
security definer
set search_path = '' as $$
declare
  b classmate.batches%rowtype;
  sem_id uuid;
begin
  perform classmate.require_owner();
  insert into classmate.batches(department_id, batch_number, academic_session)
  values (target_department, target_batch_number, target_session) returning * into b;

  if target_initial_semester is not null and target_initial_semester between 1 and 8 then
    select id into sem_id from classmate.semesters
    where batch_id = b.id and semester_number = target_initial_semester;

    if sem_id is not null then
      update classmate.semesters set status = 'active', published_at = now()
      where id = sem_id;

      insert into classmate.notification_outbox(kind, record_id, department_id, batch_id, routing_label)
      values ('semester', sem_id, b.department_id, b.id, classmate.routing_label(b.id))
      on conflict (kind, record_id) do nothing;
    end if;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id) values (auth.uid(), 'create_batch', b.id);
  return b;
end;
$$;

revoke all on function classmate.create_batch(uuid, smallint, smallint, smallint) from public, anon;
grant execute on function classmate.create_batch(uuid, smallint, smallint, smallint) to authenticated;
