begin;

create or replace function public.get_schedule_teacher_names(target_offerings uuid[])
returns table (course_offering_id uuid, teacher_names text)
language sql
stable
security definer
set search_path = ''
as $$
  select requested.offering_id,
         coalesce(string_agg(names.display_name, ', ' order by names.display_name), '')
  from unnest(target_offerings) as requested(offering_id)
  left join lateral (
    select distinct coalesce(nullif(p.display_name, ''), 'Teacher') as display_name
    from public.role_grants g
    join public.profiles p on p.id = g.profile_id
    where g.course_offering_id = requested.offering_id
      and g.role = 'teacher'
      and g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
      and public.can_access_offering(requested.offering_id)
  ) as names on true
  where public.is_active_user()
  group by requested.offering_id;
$$;

revoke all on function public.get_schedule_teacher_names(uuid[]) from public;
revoke all on function public.get_schedule_teacher_names(uuid[]) from anon;
grant execute on function public.get_schedule_teacher_names(uuid[]) to authenticated;

commit;
