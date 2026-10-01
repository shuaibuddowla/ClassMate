-- Read-only counts and blockers for the one-time public V2 import.
select
  (select count(*) from classmate.profiles where role = 'admin'
    and verification_status = 'active') as new_owner_profiles,
  (select count(*) from classmate.batches) as target_batches,
  (select count(*) from public.batches) as source_batches,
  (select count(*) from public.courses) as source_courses,
  (select count(*) from public.course_offerings offering
    join public.batch_semesters bs on bs.id = offering.batch_semester_id
    where bs.state::text = 'published' and offering.is_active) as source_offerings,
  (select count(*) from public.routine_slots where deleted_at is null) as source_routine_slots,
  (select count(*) from public.notices where state::text = 'published'
    and deleted_at is null) as source_notices,
  (select count(*) from public.notices notice
    where notice.state::text = 'published' and notice.deleted_at is null
      and ((select count(*) from public.notice_targets target
        where target.notice_id = notice.id) <> 1
        or (select count(*) from public.notice_targets target
          where target.notice_id = notice.id and target.batch_id is not null) <> 1))
    as notices_with_ambiguous_target,
  (select count(*) from public.notices notice
    join public.profiles old_author on old_author.id = notice.author_id
    left join classmate.profiles new_author
      on new_author.email = lower(old_author.email::text)
    where notice.state::text = 'published' and notice.deleted_at is null
      and new_author.id is null) as notices_without_new_author,
  (select count(*) from public.notice_attachments) as legacy_notice_images,
  (select count(*) from public.resources where deleted_at is null) as legacy_resources;
