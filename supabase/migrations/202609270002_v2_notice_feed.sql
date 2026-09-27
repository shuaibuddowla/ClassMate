-- Read model for the Android notice feed. The function exposes only notices
-- the current authenticated profile may read and keeps author profiles private.

create or replace function public.list_visible_notices()
returns table (
  id uuid,
  author_id uuid,
  author_name text,
  title text,
  body text,
  priority public.notice_priority,
  globally_pinned boolean,
  published_at timestamptz,
  created_at timestamptz,
  updated_at timestamptz,
  like_count bigint,
  is_liked boolean,
  is_personally_pinned boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    n.id,
    n.author_id,
    coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1)) as author_name,
    n.title,
    n.body,
    n.priority,
    n.globally_pinned,
    n.published_at,
    n.created_at,
    n.updated_at,
    (select count(*) from public.notice_likes nl where nl.notice_id = n.id) as like_count,
    exists (
      select 1
      from public.notice_likes nl
      where nl.notice_id = n.id
        and nl.profile_id = public.current_profile_id()
    ) as is_liked,
    exists (
      select 1
      from public.notice_pins np
      where np.notice_id = n.id
        and np.profile_id = public.current_profile_id()
    ) as is_personally_pinned
  from public.notices n
  join public.profiles p on p.id = n.author_id
  where public.is_active_user()
    and n.state = 'published'
    and n.deleted_at is null
    and public.can_read_notice(n.id)
  order by n.globally_pinned desc, n.published_at desc nulls last, n.created_at desc
  limit 100;
$$;

revoke all on function public.list_visible_notices() from public;
grant execute on function public.list_visible_notices() to authenticated;

