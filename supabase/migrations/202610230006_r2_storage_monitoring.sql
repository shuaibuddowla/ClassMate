begin;

-- Function: classmate.get_r2_storage_stats()
-- Aggregates Cloudflare R2 resource storage metrics, usage breakdown, and quota status.
create or replace function classmate.get_r2_storage_stats()
returns jsonb
language plpgsql
security definer
set search_path = classmate, public
as $$
declare
  v_uid uuid;
  v_role classmate.user_role;
  v_is_cr boolean;
  v_total_bytes bigint;
  v_total_files bigint;
  v_quota_bytes bigint := 10737418240; -- 10 GB in bytes (10 * 1024^3)
  v_limit_bytes bigint := 10630044057; -- 9.9 GB in bytes (9.9 * 1024^3)
  v_warning_bytes bigint := 8589934592; -- 8.0 GB in bytes (8.0 * 1024^3)
  v_status text;
  v_courses jsonb;
  v_uploaders jsonb;
begin
  v_uid := auth.uid();
  if v_uid is null then
    if current_user in ('postgres', 'service_role') or auth.role() = 'service_role' then
      -- Authorized service role or superuser execution
      null;
    else
      raise exception 'Unauthorized';
    end if;
  else
    select role, is_cr into v_role, v_is_cr
    from classmate.profiles
    where id = v_uid;

    if v_role::text not in ('admin', 'superadmin', 'owner', 'teacher') and not coalesce(v_is_cr, false) then
      raise exception 'Forbidden: Insufficient privileges';
    end if;
  end if;

  -- 1. Overall active R2 storage calculations
  select
    coalesce(sum(size_bytes), 0),
    count(*)
  into v_total_bytes, v_total_files
  from classmate.file_metadata
  where status = 'active'
    and r2_object_key is not null;

  if v_total_bytes >= v_limit_bytes then
    v_status := 'exceeded';
  elsif v_total_bytes >= v_warning_bytes then
    v_status := 'warning';
  else
    v_status := 'normal';
  end if;

  -- 2. Breakdown by Course
  select coalesce(jsonb_agg(c_row), '[]'::jsonb)
  into v_courses
  from (
    select
      coalesce(sc.title_override, c.course_title, 'General Resources') as course_title,
      coalesce(sc.code_override, c.course_code, 'GEN') as course_code,
      count(f.id)::int as file_count,
      coalesce(sum(f.size_bytes), 0)::bigint as total_bytes
    from classmate.file_metadata f
    left join classmate.semester_courses sc on f.semester_course_id = sc.id
    left join classmate.courses c on sc.course_id = c.id
    where f.status = 'active'
      and f.r2_object_key is not null
    group by coalesce(sc.title_override, c.course_title, 'General Resources'),
             coalesce(sc.code_override, c.course_code, 'GEN')
    order by total_bytes desc
    limit 15
  ) c_row;

  -- 3. Breakdown by Top Contributors / Uploaders
  select coalesce(jsonb_agg(u_row), '[]'::jsonb)
  into v_uploaders
  from (
    select
      coalesce(nullif(trim(p.full_name), ''), 'ClassMate Contributor') as full_name,
      coalesce(p.role::text, 'member') as role,
      count(f.id)::int as file_count,
      coalesce(sum(f.size_bytes), 0)::bigint as total_bytes
    from classmate.file_metadata f
    left join classmate.profiles p on f.uploaded_by = p.id
    where f.status = 'active'
      and f.r2_object_key is not null
    group by p.id, p.full_name, p.role
    order by total_bytes desc
    limit 15
  ) u_row;

  return jsonb_build_object(
    'total_bytes', v_total_bytes,
    'total_files', v_total_files,
    'quota_bytes', v_quota_bytes,
    'limit_bytes', v_limit_bytes,
    'warning_bytes', v_warning_bytes,
    'status', v_status,
    'courses', v_courses,
    'uploaders', v_uploaders
  );
end;
$$;

-- Public wrapper for PostgREST RPC
create or replace function public.get_r2_storage_stats()
returns jsonb
language sql
security definer
as $$
  select classmate.get_r2_storage_stats();
$$;

grant execute on function classmate.get_r2_storage_stats() to authenticated, service_role;
grant execute on function public.get_r2_storage_stats() to authenticated, service_role;

notify pgrst, 'reload schema';

commit;
