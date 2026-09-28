begin;

-- A course can have a display teacher even when that teacher does not have a
-- ClassMate account or a teacher role grant for the offering yet.
alter table public.courses add column teacher_name text;

create or replace function public.get_schedule_teacher_names(target_offerings uuid[])
returns table (course_offering_id uuid, teacher_names text)
language sql
stable
security definer
set search_path = ''
as $$
  select requested.offering_id,
         coalesce(nullif(string_agg(names.display_name, ', ' order by names.display_name), ''),
                  nullif(btrim(c.teacher_name), ''), '')
  from unnest(target_offerings) as requested(offering_id)
  join public.course_offerings o on o.id = requested.offering_id
  join public.courses c on c.id = o.course_id
  left join lateral (
    select distinct coalesce(nullif(p.display_name, ''), 'Teacher') as display_name
    from public.role_grants g
    join public.profiles p on p.id = g.profile_id
    where g.course_offering_id = requested.offering_id
      and g.role = 'teacher'
      and g.revoked_at is null
      and g.starts_at <= now()
      and (g.expires_at is null or g.expires_at > now())
  ) as names on true
  where public.is_active_user()
    and public.can_access_offering(requested.offering_id)
  group by requested.offering_id, c.teacher_name;
$$;

commit;
