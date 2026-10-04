begin;
-- CRs already manage the batch catalog/routine. Scope their academic posting
-- to active offerings in that same batch; teacher assignment rules are unchanged.
create or replace function classmate.can_post(target_batch uuid, target_course uuid default null)
returns boolean language sql stable security definer set search_path = '' as $$
  select classmate.is_owner() or exists (
    select 1 from classmate.profiles p where p.id=(select auth.uid())
      and p.verification_status='active' and (
        (p.role='student' and p.is_cr and p.cr_batch_id=target_batch
          and (p.cr_valid_until is null or p.cr_valid_until>now())
          and exists(select 1 from classmate.batches b where b.id=target_batch and b.is_active)
          and (target_course is null or exists (
            select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
            where sc.id=target_course and s.batch_id=target_batch and s.status='active')))
        or (target_course is not null and classmate.can_manage_course(target_course)
          and exists(select 1 from classmate.semester_courses sc join classmate.semesters s on s.id=sc.semester_id
            where sc.id=target_course and s.batch_id=target_batch and s.status='active'))
      )
  );
$$;
notify pgrst,'reload schema';
commit;
