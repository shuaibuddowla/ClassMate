-- Deliberately expose only the developer's public academic identity.
create or replace function classmate.developer_profile() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'full_name', p.full_name, 'student_id', p.student_id,
    'department', d.name, 'department_code', d.code,
    'batch_number', b.batch_number, 'academic_session', b.academic_session,
    'avatar_url', case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') else null end)
  from classmate.profiles p
  join classmate.app_owners o on o.email=p.email and o.active
  join auth.users u on u.id=p.id
  left join classmate.departments d on d.id=p.department_id
  left join classmate.batches b on b.id=p.batch_id
  where auth.uid() is not null
  order by p.id limit 1;
$$;
revoke all on function classmate.developer_profile() from public,anon;
grant execute on function classmate.developer_profile() to authenticated;
