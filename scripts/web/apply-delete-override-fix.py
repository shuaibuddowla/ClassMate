import pathlib, sys
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import query, PRODUCTION, STAGING

sql = """
drop function if exists classmate.delete_batch_override(text);
drop function if exists classmate.delete_batch_override(text,uuid);

create or replace function classmate.delete_batch_override(
  target_student_id text default null,
  target_override_id uuid default null
)
returns jsonb language plpgsql security definer set search_path = '' as $fn$
declare
  norm_id text := upper(trim(coalesce(target_student_id, '')));
  deleted_row classmate.batch_overrides%rowtype;
begin
  perform classmate.require_owner();

  if target_override_id is not null then
    delete from classmate.batch_overrides
    where id = target_override_id
    returning * into deleted_row;
  elsif norm_id <> '' then
    delete from classmate.batch_overrides
    where student_id = norm_id
    returning * into deleted_row;
  else
    raise exception 'Either target_student_id or target_override_id must be provided' using errcode = '22023';
  end if;

  if deleted_row.id is null then
    raise exception 'Batch override not found' using errcode = '22023';
  end if;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'delete_batch_override',
    deleted_row.effective_batch_id,
    jsonb_build_object(
      'student_id', deleted_row.student_id,
      'deleted_override_id', deleted_row.id
    )
  );

  return jsonb_build_object(
    'student_id', deleted_row.student_id,
    'deleted', true
  );
end $fn$;

revoke all on function classmate.delete_batch_override(text,uuid) from public, anon;
grant execute on function classmate.delete_batch_override(text,uuid) to authenticated;
notify pgrst, 'reload schema';
"""

for ref, env_name in [(STAGING, 'staging'), (PRODUCTION, 'production')]:
    res = query(ref, sql)
    print(f"Applied delete_batch_override update to {env_name}: {res}")
