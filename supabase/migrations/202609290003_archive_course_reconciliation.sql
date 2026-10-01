begin;

-- Firestore is the library/course source of truth. Keep dependent notices,
-- files, timetable history, and teacher grants recoverable on course removal.
alter table public.course_offerings
  add column if not exists archive_course_id text;
alter table public.course_offerings
  add column if not exists is_active boolean not null default true;
create index if not exists course_offerings_archive_course_id_idx
  on public.course_offerings (archive_course_id)
  where archive_course_id is not null;

create or replace function public.sync_archive_catalog(
  snapshot jsonb,
  apply_changes boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_department uuid;
  v_university uuid;
  v_batches uuid[] := array[]::uuid[];
  v_batch uuid;
  v_semester uuid;
  v_batch_semester uuid;
  v_course uuid;
  v_existing_offering uuid;
  v_item jsonb;
  v_batch_item jsonb;
  v_code text;
  v_archive_id text;
  v_kind public.course_kind;
  v_website_count int := 0;
  v_stale_count int := 0;
  v_deactivated_count int := 0;
  v_synced_count int := 0;
begin
  if jsonb_typeof(snapshot) <> 'object'
     or jsonb_typeof(snapshot->'batches') <> 'array'
     or jsonb_typeof(snapshot->'courses') <> 'array'
     or jsonb_array_length(snapshot->'batches') = 0
     or jsonb_array_length(snapshot->'courses') > 5000 then
    raise exception 'Invalid Archive catalogue snapshot';
  end if;

  select d.id, d.university_id into v_department, v_university
  from public.departments d
  where lower(d.code) = 'cse'
  limit 1;
  if v_department is null then raise exception 'CSE department is not configured'; end if;

  for v_batch_item in select value from jsonb_array_elements(snapshot->'batches') loop
    select b.id into v_batch
    from public.batches b
    where b.department_id = v_department
      and regexp_replace(lower('cse' || b.cohort_code), '[^a-z0-9]', '', 'g') =
          regexp_replace(lower(v_batch_item->>'id'), '[^a-z0-9]', '', 'g')
    limit 1;
    if v_batch is not null and not v_batch = any(v_batches) then
      v_batches := array_append(v_batches, v_batch);
    end if;
  end loop;
  if cardinality(v_batches) = 0 then
    raise exception 'No Archive batches match Supabase; refusing to remove courses';
  end if;

  select count(*) into v_website_count
  from jsonb_array_elements(snapshot->'courses') as x(item)
  where item->>'category' is distinct from 'syllabus';

  -- Preview uses the same batch boundary but never mutates anything.
  if not apply_changes then
    select count(*) into v_stale_count
    from public.course_offerings o
    join public.batch_semesters bs on bs.id = o.batch_semester_id
    join public.courses c on c.id = o.course_id
    join public.semesters s on s.id = bs.semester_id
    join public.batches b on b.id = bs.batch_id
    where bs.batch_id = any(v_batches) and o.is_active
      and not exists (
        select 1 from jsonb_array_elements(snapshot->'courses') as x(item)
        where item->>'category' is distinct from 'syllabus'
          and regexp_replace(lower(item->>'batchId'), '[^a-z0-9]', '', 'g') =
              regexp_replace(lower('cse' || b.cohort_code), '[^a-z0-9]', '', 'g')
          and (item->>'semesterNumber')::int = s.ordinal
          and (item->>'id' = o.archive_course_id or
               upper(coalesce(nullif(item->>'courseCode', ''), 'ARCH-' || left(item->>'id', 12))) = upper(c.code))
      );
    return jsonb_build_object('apply', false, 'matchedBatches', cardinality(v_batches),
      'archiveCourses', v_website_count, 'staleOfferings', v_stale_count);
  end if;

  for v_item in select value from jsonb_array_elements(snapshot->'courses') loop
    if v_item->>'category' = 'syllabus' then continue; end if;
    v_archive_id := v_item->>'id';
    if v_archive_id is null or v_archive_id = '' or
       coalesce(v_item->>'courseName', '') = '' or
       (v_item->>'semesterNumber')::int not between 1 and 8 then
      raise exception 'Invalid Archive course in snapshot';
    end if;
    select b.id into v_batch
    from public.batches b
    where b.id = any(v_batches)
      and regexp_replace(lower('cse' || b.cohort_code), '[^a-z0-9]', '', 'g') =
          regexp_replace(lower(v_item->>'batchId'), '[^a-z0-9]', '', 'g')
    limit 1;
    if v_batch is null then continue; end if;
    select s.id into v_semester from public.semesters s
    where s.university_id = v_university
      and s.ordinal = (v_item->>'semesterNumber')::int limit 1;
    if v_semester is null then raise exception 'Archive semester is not configured'; end if;
    insert into public.batch_semesters (batch_id, semester_id, state, published_at)
    values (v_batch, v_semester, 'published', now())
    on conflict (batch_id, semester_id)
    do update set state = 'published', published_at = coalesce(public.batch_semesters.published_at, now())
    returning id into v_batch_semester;

    v_code := upper(coalesce(nullif(trim(v_item->>'courseCode'), ''), 'ARCH-' || left(v_archive_id, 12)));
    v_kind := case when v_item->>'category' = 'lab' then 'lab'::public.course_kind
                   else 'theory'::public.course_kind end;
    insert into public.courses (department_id, code, name, kind, is_active)
    values (v_department, v_code, trim(v_item->>'courseName'), v_kind, true)
    on conflict (department_id, code)
    do update set name = excluded.name, kind = excluded.kind, is_active = true
    returning id into v_course;

    select o.id into v_existing_offering from public.course_offerings o
    where o.batch_semester_id = v_batch_semester and o.archive_course_id = v_archive_id
    limit 1;
    if v_existing_offering is not null then
      update public.course_offerings set course_id = v_course, is_active = true
      where id = v_existing_offering;
    else
      select o.id into v_existing_offering from public.course_offerings o
      where o.batch_semester_id = v_batch_semester and o.course_id = v_course
      limit 1;
      if v_existing_offering is null then
        insert into public.course_offerings (batch_semester_id, course_id, archive_course_id, is_active)
        values (v_batch_semester, v_course, v_archive_id, true);
      end if;
    end if;
    update public.course_offerings set archive_course_id = v_archive_id, is_active = true
    where batch_semester_id = v_batch_semester and course_id = v_course;
    v_synced_count := v_synced_count + 1;
  end loop;

  -- Hide stale offerings rather than cascading deletion into other content.
  with deactivated as (
    update public.course_offerings o set is_active = false
    from public.batch_semesters bs, public.semesters s, public.batches b
    where o.batch_semester_id = bs.id and bs.semester_id = s.id and bs.batch_id = b.id
      and bs.batch_id = any(v_batches) and o.is_active
      and not exists (
        select 1 from jsonb_array_elements(snapshot->'courses') as x(item)
        where item->>'category' is distinct from 'syllabus'
          and item->>'id' = o.archive_course_id
          and regexp_replace(lower(item->>'batchId'), '[^a-z0-9]', '', 'g') =
              regexp_replace(lower('cse' || b.cohort_code), '[^a-z0-9]', '', 'g')
          and (item->>'semesterNumber')::int = s.ordinal
      )
    returning o.id
  ) select count(*) into v_deactivated_count from deactivated;

  update public.courses c set is_active = false
  where c.department_id = v_department
    and c.is_active
    and not exists (select 1 from public.course_offerings o
      where o.course_id = c.id and o.is_active);
  return jsonb_build_object('apply', true, 'matchedBatches', cardinality(v_batches),
    'archiveCourses', v_website_count, 'syncedCourses', v_synced_count,
    'deactivatedOfferings', v_deactivated_count);
end;
$$;

create or replace function public.can_access_offering(target_offering uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.course_offerings o
    join public.courses c on c.id = o.course_id
    join public.batch_semesters bs on bs.id = o.batch_semester_id
    where o.id = target_offering and o.is_active and c.is_active
      and (
        (o.section_id is null and public.can_access_batch(bs.batch_id))
        or (o.section_id is not null and public.can_access_section(o.section_id))
        or public.has_active_role('teacher', null, null, null, o.id)
      )
  );
$$;

revoke all on function public.sync_archive_catalog(jsonb, boolean) from public, anon, authenticated;
grant execute on function public.sync_archive_catalog(jsonb, boolean) to service_role;

commit;
