begin;
-- PL/pgSQL retains prepared authorization queries across repeated row checks.
-- The predicates and security-definer boundaries remain unchanged.

create or replace function classmate.is_owner() returns boolean language plpgsql stable security definer
set search_path = '' as $$
begin
  return exists (
    select 1 from classmate.app_owners o join auth.users u on lower(u.email) = o.email
    where u.id = (select auth.uid()) and u.email_confirmed_at is not null and o.active
  );
end;
$$;

create or replace function classmate.is_active() returns boolean language plpgsql stable security definer
set search_path = '' as $$
begin
  return exists (select 1 from classmate.profiles p where p.id = (select auth.uid())
    and p.verification_status = 'active'
    and (p.role = 'student' or (p.role = 'admin' and classmate.is_owner())
      or (p.role = 'teacher' and exists (select 1 from classmate.teacher_allowlist a
        where a.email = p.email and a.active))));
end;
$$;

create or replace function classmate.can_read_batch(target_batch uuid) returns boolean language plpgsql stable security definer
set search_path = '' as $$
begin
  return classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id = (select auth.uid())
      and p.verification_status = 'active'
      and ((p.role = 'student' and p.batch_id = target_batch)
        or (p.role = 'teacher' and exists (
          select 1 from classmate.teacher_course_assignments a
          join classmate.semester_courses sc on sc.id = a.semester_course_id
          join classmate.semesters s on s.id = sc.semester_id
          join classmate.batches b on b.id = s.batch_id
          join classmate.teacher_allowlist t on t.email = p.email
            and t.department_id = b.department_id and t.active
          where a.teacher_id = p.id and a.active and s.batch_id = target_batch)))
  );
end;
$$;

create or replace function classmate.can_manage_course(target_semester_course uuid)
returns boolean language plpgsql stable security definer set search_path = '' as $$
begin
  return classmate.is_owner() or exists (
    select 1 from classmate.teacher_course_assignments a
    join classmate.profiles p on p.id = a.teacher_id
    join classmate.teacher_allowlist t on t.email = p.email and t.active
    join classmate.semester_courses sc on sc.id = a.semester_course_id
    join classmate.semesters s on s.id = sc.semester_id
    join classmate.batches b on b.id = s.batch_id and b.department_id = t.department_id
    where a.teacher_id = (select auth.uid()) and a.semester_course_id = target_semester_course
      and a.active and p.role = 'teacher' and p.verification_status = 'active'
  );
end;
$$;

create or replace function classmate.can_read_notice(target_notice uuid)
returns boolean language plpgsql stable security definer set search_path = '' as $$
begin
  return exists (
    select 1 from classmate.notices n
    where n.id = target_notice and classmate.can_read_batch(n.batch_id)
      and (n.semester_course_id is null
        or classmate.can_manage_course(n.semester_course_id)
        or exists (select 1 from classmate.profiles p
          where p.id = (select auth.uid()) and p.role = 'student'
            and p.batch_id = n.batch_id and p.verification_status = 'active'))
  );
end;
$$;


create or replace function classmate.notice_feed_details(target_ids uuid[]) returns jsonb
language sql stable security definer set search_path='' as $$
with permitted as materialized (
 select n.id,n.author_id from classmate.notices n
 where n.id=any(target_ids[1:100]) and classmate.can_read_notice(n.id)
), engagement as (
 select n.id notice_id,
 (select count(*) from classmate.notice_reactions r where r.notice_id=n.id and r.liked) like_count,
 (select count(*) from classmate.notice_comments c where c.notice_id=n.id and c.deleted_at is null) comment_count,
 coalesce(own.liked,false) is_liked,coalesce(own.pinned,false) is_pinned,own.reminder_at
 from permitted n left join classmate.notice_reactions own on own.notice_id=n.id and own.profile_id=(select auth.uid())
), authors as (
 select n.id notice_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') author_name,
 case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end avatar_url
 from permitted n join classmate.profiles p on p.id=n.author_id join auth.users u on u.id=p.id
), reads as (
 select n.id notice_id,(select count(*) from classmate.notice_reads r where r.notice_id=n.id) read_count,
 exists(select 1 from classmate.notice_reads r where r.notice_id=n.id and r.profile_id=(select auth.uid())) read_by_me from permitted n
), previews as (
 select n.id notice_id,r.profile_id,coalesce(nullif(trim(p.full_name),''),'ClassMate member') reader_name,
 case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://' then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end avatar_url,r.read_at
 from permitted n cross join lateral (select profile_id,read_at from classmate.notice_reads where notice_id=n.id order by read_at desc,profile_id limit 4) r
 join classmate.profiles p on p.id=r.profile_id left join auth.users u on u.id=p.id
)
select jsonb_build_object(
 'engagement',coalesce((select jsonb_agg(to_jsonb(e)) from engagement e),'[]'::jsonb),
 'authors',coalesce((select jsonb_agg(to_jsonb(a)) from authors a),'[]'::jsonb),
 'reads',coalesce((select jsonb_agg(to_jsonb(r)) from reads r),'[]'::jsonb),
 'previews',coalesce((select jsonb_agg(to_jsonb(p)) from previews p),'[]'::jsonb));
$$;
notify pgrst,'reload schema';
commit;
