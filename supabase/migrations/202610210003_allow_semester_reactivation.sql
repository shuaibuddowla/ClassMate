create or replace function classmate.publish_semester(target_semester uuid)
returns classmate.semesters
language plpgsql
security definer
set search_path = ''
as $$
declare
  s classmate.semesters%rowtype;
begin
  perform classmate.require_owner();
  select * into s from classmate.semesters where id = target_semester;
  if s.id is null then raise exception 'Semester not found'; end if;
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
