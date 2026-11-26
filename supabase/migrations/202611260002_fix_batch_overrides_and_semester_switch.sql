-- 1. Fix classmate.set_batch_override to prevent 23505 duplicate email/student_id conflicts
create or replace function classmate.set_batch_override(
  target_student_id text,
  target_batch uuid,
  target_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $$
declare
  raw_input text := trim(coalesce(target_student_id, ''));
  v_email text := null;
  norm_id text := null;
  batch_row classmate.batches%rowtype;
  override_row classmate.batch_overrides%rowtype;
  existing_by_id classmate.batch_overrides%rowtype;
  existing_by_email classmate.batch_overrides%rowtype;
  profile_updated boolean := false;
  matched_profile classmate.profiles%rowtype;
  ru_matched text[];
  mbstu_matched text[];
begin
  if not classmate.can_manage_batch(target_batch) then
    raise exception 'Unauthorized to manage overrides for this batch' using errcode = '42501';
  end if;

  select * into batch_row from classmate.batches where id = target_batch and is_active;
  if batch_row.id is null then
    raise exception 'Target batch not found or inactive' using errcode = '22023';
  end if;

  -- Determine if raw_input is an institutional email or student ID
  if raw_input like '%@%' then
    v_email := lower(raw_input);
    if v_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$' then
      raise exception 'Enter a valid university email address' using errcode = '22023';
    end if;

    ru_matched := regexp_match(v_email, '^(?:s)?([0-9]{8,10})@ru[.]ac[.]bd$');
    mbstu_matched := regexp_match(v_email, '^([a-z]+)([0-9]{2})([0-9]{3,4})@mbstu[.]ac[.]bd$');
    if ru_matched is not null then
      norm_id := ru_matched[1];
    elsif mbstu_matched is not null then
      norm_id := upper(mbstu_matched[1] || mbstu_matched[2] || mbstu_matched[3]);
    end if;

    select * into matched_profile from classmate.profiles
    where lower(trim(email)) = v_email and role = 'student' for update;

    if matched_profile.id is not null and matched_profile.student_id is not null and trim(matched_profile.student_id) <> '' then
      norm_id := upper(trim(matched_profile.student_id));
    end if;
  else
    norm_id := upper(raw_input);
    if length(norm_id) not between 4 and 30 or norm_id !~ '^[A-Za-z0-9-]{4,30}$' then
      raise exception 'Enter a valid student ID (e.g. CE23045) or university email' using errcode = '22023';
    end if;

    select * into matched_profile from classmate.profiles
    where upper(trim(student_id)) = norm_id and role = 'student' for update;

    if matched_profile.id is not null and matched_profile.email is not null and trim(matched_profile.email) <> '' then
      v_email := lower(trim(matched_profile.email));
    end if;
  end if;

  -- Look up existing override rows safely by student_id and/or email to avoid unique conflicts
  if norm_id is not null then
    select * into existing_by_id
    from classmate.batch_overrides
    where upper(trim(student_id)) = norm_id
    limit 1
    for update;
  end if;

  if v_email is not null then
    select * into existing_by_email
    from classmate.batch_overrides
    where lower(trim(email)) = v_email
    limit 1
    for update;
  end if;

  if existing_by_id.id is not null and existing_by_email.id is not null and existing_by_id.id <> existing_by_email.id then
    -- Merge duplicate row if one was created by email and another by student_id
    delete from classmate.batch_overrides where id = existing_by_email.id;
  end if;

  if existing_by_id.id is not null then
    update classmate.batch_overrides
    set student_id = coalesce(norm_id, existing_by_id.student_id),
        email = coalesce(v_email, existing_by_id.email),
        effective_batch_id = batch_row.id,
        reason = nullif(trim(target_reason), ''),
        added_by = auth.uid(),
        created_at = now()
    where id = existing_by_id.id
    returning * into override_row;
  elsif existing_by_email.id is not null then
    update classmate.batch_overrides
    set student_id = coalesce(norm_id, existing_by_email.student_id),
        email = coalesce(v_email, existing_by_email.email),
        effective_batch_id = batch_row.id,
        reason = nullif(trim(target_reason), ''),
        added_by = auth.uid(),
        created_at = now()
    where id = existing_by_email.id
    returning * into override_row;
  else
    insert into classmate.batch_overrides (student_id, email, effective_batch_id, reason, added_by, created_at)
    values (norm_id, v_email, batch_row.id, nullif(trim(target_reason), ''), auth.uid(), now())
    returning * into override_row;
  end if;

  -- If student already has a registered profile, immediately move them to the target batch
  if matched_profile.id is not null then
    update classmate.profiles
    set batch_id = batch_row.id,
        department_id = batch_row.department_id,
        academic_session = batch_row.academic_session,
        verification_status = 'active',
        rejection_reason = null,
        is_cr = case when is_cr and cr_batch_id is distinct from batch_row.id then false else is_cr end,
        cr_batch_id = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_batch_id end,
        cr_valid_until = case when is_cr and cr_batch_id is distinct from batch_row.id then null else cr_valid_until end,
        updated_at = now()
    where id = matched_profile.id;
    profile_updated := true;
  end if;

  insert into classmate.audit_log(actor_id, action, target_id, details)
  values (
    auth.uid(),
    'set_batch_override',
    batch_row.id,
    jsonb_build_object(
      'student_id', norm_id,
      'email', v_email,
      'effective_batch_id', batch_row.id,
      'reason', override_row.reason,
      'profile_updated', profile_updated,
      'profile_id', matched_profile.id
    )
  );

  return jsonb_build_object(
    'id', override_row.id,
    'student_id', override_row.student_id,
    'email', override_row.email,
    'effective_batch_id', override_row.effective_batch_id,
    'reason', override_row.reason,
    'created_at', override_row.created_at,
    'profile_updated', profile_updated
  );
end;
$$;

-- 2. Scope assessments by semester_id so switching active semester cleanly isolates assessments
alter table classmate.assessments
  add column if not exists semester_id uuid references classmate.semesters(id) on delete set null;

create index if not exists idx_assessments_batch_semester
  on classmate.assessments (batch_id, semester_id, deadline_date);

-- Backfill semester_id on existing assessments
update classmate.assessments a
set semester_id = coalesce(
  (select sc.semester_id from classmate.semester_courses sc where sc.id = a.course_id limit 1),
  (select s.id from classmate.semesters s where s.batch_id = a.batch_id and s.status = 'active' limit 1)
)
where a.semester_id is null;

create or replace function classmate.set_assessment_semester_id()
returns trigger
language plpgsql
security definer
set search_path to ''
as $$
begin
  if new.semester_id is null and new.course_id is not null then
    select sc.semester_id into new.semester_id
    from classmate.semester_courses sc
    where sc.id = new.course_id
    limit 1;
  end if;

  if new.semester_id is null and new.batch_id is not null then
    select s.id into new.semester_id
    from classmate.semesters s
    where s.batch_id = new.batch_id and s.status = 'active'
    limit 1;
  end if;

  return new;
end;
$$;

drop trigger if exists trg_assessments_set_semester on classmate.assessments;
create trigger trg_assessments_set_semester
  before insert on classmate.assessments
  for each row
  execute function classmate.set_assessment_semester_id();

-- 3. Enhance publish_semester: switch active semester cleanly & close any lingering OPEN attendance sessions
create or replace function classmate.publish_semester(target_semester uuid)
returns classmate.semesters
language plpgsql
security definer
set search_path to ''
as $$
declare
  s classmate.semesters%rowtype;
begin
  select * into s from classmate.semesters where id = target_semester;
  if s.id is null then raise exception 'Semester not found'; end if;

  if not (classmate.is_owner() or classmate.can_manage_batch(s.batch_id)) then
    raise exception 'Permission denied: Only Admin or the active CR can publish semesters' using errcode = '42501';
  end if;

  perform 1 from classmate.batches where id = s.batch_id for update;
  select * into s from classmate.semesters where id = target_semester for update;

  if s.status = 'active' then return s; end if;

  update classmate.semesters set status = 'completed'
  where batch_id = s.batch_id and status = 'active' and id <> s.id;

  update classmate.semesters set status = 'active', published_at = coalesce(s.published_at, now())
  where id = s.id returning * into s;

  -- Close any open attendance sessions belonging to previous semesters of this batch
  update classmate.attendance_sessions sess
  set status = 'CLOSED',
      closed_at = coalesce(sess.closed_at, now())
  where sess.status = 'OPEN'
    and exists (
      select 1 from classmate.semester_courses sc
      join classmate.semesters sem on sem.id = sc.semester_id
      where sem.batch_id = s.batch_id
        and sem.id <> s.id
        and (
          sess.semester_course_id = sc.id
          or exists (
            select 1 from classmate.routine_slots rs
            where rs.id = sess.routine_slot_id and rs.semester_course_id = sc.id
          )
        )
    );

  insert into classmate.notification_outbox(kind, record_id, department_id, batch_id, routing_label)
  select 'semester', s.id, b.department_id, b.id, classmate.routing_label(b.id)
  from classmate.batches b where b.id = s.batch_id
  on conflict (kind, record_id) do nothing;

  insert into classmate.audit_log(actor_id, action, target_id)
  values (auth.uid(), 'publish_semester', s.id);

  return s;
end;
$$;
