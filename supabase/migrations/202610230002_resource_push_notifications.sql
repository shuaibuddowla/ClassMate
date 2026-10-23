begin;

-- 1. Fix course name resolution in broadcast_resource_to_telegram
create or replace function classmate.broadcast_resource_to_telegram()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_batch_num int;
  v_secret text;
  v_course_title text := null;
  v_course_code text := null;
  v_uploader_name text := 'ClassMate Contributor';
  v_should_broadcast boolean := false;
begin
  if tg_op = 'INSERT' and new.status = 'active' then
    v_should_broadcast := true;
  elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'active' then
    v_should_broadcast := true;
  end if;

  if not v_should_broadcast then
    return new;
  end if;

  -- Strictly check if target batch is CSE 22
  select batch_number into v_batch_num from classmate.batches where id = new.batch_id;
  if v_batch_num is distinct from 22 then
    return new;
  end if;

  -- Get dispatcher secret from vault
  select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'classmate_dispatch_secret' limit 1;
  if v_secret is null then
    return new;
  end if;

  -- Resolve course title and code by joining with classmate.courses
  if new.semester_course_id is not null then
    select coalesce(sc.title_override, c.course_title), coalesce(sc.code_override, c.course_code)
    into v_course_title, v_course_code
    from classmate.semester_courses sc
    join classmate.courses c on c.id = sc.course_id
    where sc.id = new.semester_course_id limit 1;
  end if;

  -- Resolve uploader name
  if new.uploaded_by is not null then
    select coalesce(nullif(trim(p.full_name), ''), 'ClassMate Contributor') into v_uploader_name
    from classmate.profiles p
    where p.id = new.uploaded_by limit 1;
  end if;

  -- Asynchronously post to Cloudflare Worker Telegram broadcast endpoint
  perform net.http_post(
    url := 'https://classmate-secure-api.shuaibuddowla-personal.workers.dev/internal/telegram-broadcast',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Dispatch-Secret', v_secret,
      'User-Agent', 'ClassMate-Database/1.0'
    ),
    body := jsonb_build_object(
      'resource_id', new.id,
      'title', new.title,
      'category', new.category,
      'course_title', v_course_title,
      'course_code', v_course_code,
      'uploader_name', v_uploader_name,
      'file_type', new.file_type,
      'batch_number', 22
    )
  );

  return new;
exception when others then
  return new;
end;
$$;

-- 2. Update academic_notification_job_context so resource announcements display
--    Title: 'New Resources'
--    Body: '<File Title> · <Course Title> (<Course Code>)'
create or replace function classmate.academic_notification_job_context(
  target_event uuid,
  target_hash text,
  target_lease uuid
) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  j classmate.notification_jobs%rowtype;
  e classmate.notification_outbox%rowtype;
  content jsonb;
  sess record;
  mark_info record;
  notice_res record;
begin
  select * into j from classmate.notification_jobs
  where event_id = target_event and token_hash = target_hash and lease_id = target_lease
    and state = 'leased' and lease_until > now();
  if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
    return null;
  end if;

  select * into e from classmate.notification_outbox where id = target_event;

  -- 1. Attendance Marked Confirmation
  if e.kind = 'attendance_marked' then
    select ar.id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into mark_info
    from classmate.attendance_records ar
    join classmate.attendance_sessions s on s.id = ar.session_id
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where ar.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
      'title', 'Attendance Recorded',
      'body', 'Your attendance for ' || mark_info.course_title || ' was marked present in the app.',
      'kind', 'attendance_marked',
      'record_id', mark_info.id::text,
      'failures', j.failures
    );
  end if;

  -- 2. Attendance Open Announcement
  if e.kind = 'attendance_open' then
    select s.id as session_id, s.routine_slot_id,
           coalesce(sc.title_override, c.course_title, 'Class') as course_title,
           coalesce(sc.code_override, c.course_code, '') as course_code
    into sess
    from classmate.attendance_sessions s
    left join classmate.routine_slots rs on rs.id = s.routine_slot_id
    join classmate.semester_courses sc on sc.id = coalesce(s.semester_course_id, rs.semester_course_id)
    join classmate.courses c on c.id = sc.course_id
    where s.id = e.record_id;

    if not found then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
      'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
      'title', case when sess.course_code <> '' then 'Attendance Live · ' || sess.course_code else 'Attendance Live' end,
      'body', 'Punch your cards for ' || coalesce(sess.course_title, 'Class') || ' attendance.',
      'kind', 'attendance_open',
      'session_id', sess.session_id::text,
      'routine_slot_id', coalesce(sess.routine_slot_id::text, ''),
      'course_code', coalesce(sess.course_code, ''),
      'course_title', coalesce(sess.course_title, 'Class'),
      'route', 'my_attendance',
      'failures', j.failures
    );
  end if;

  -- 3. Dedicated Resource Notice (File upload or Google Drive link)
  if e.kind = 'notice' then
    select n.*, f.title as file_title,
           coalesce(sc.title_override, c.course_title) as course_title,
           coalesce(sc.code_override, c.course_code) as course_code
    into notice_res
    from classmate.notices n
    left join classmate.file_metadata f on f.id = n.resource_id
    left join classmate.semester_courses sc on sc.id = coalesce(n.semester_course_id, f.semester_course_id)
    left join classmate.courses c on c.id = sc.course_id
    where n.id = e.record_id and not n.silent;

    if not found then return null; end if;

    if notice_res.resource_id is not null and notice_res.file_title is not null then
      return jsonb_build_object(
        'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
        'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
        'title', 'New Resources',
        'body', case
                  when notice_res.course_title is not null and notice_res.course_code is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title || ' (' || notice_res.course_code || ')'
                  when notice_res.course_title is not null then
                    notice_res.file_title || ' · ' || notice_res.course_title
                  else
                    notice_res.file_title || ' is now available'
                end,
        'kind', 'notice',
        'record_id', notice_res.id::text,
        'resource_id', notice_res.resource_id::text,
        'failures', j.failures
      );
    end if;

    content := to_jsonb(notice_res);
  else
    case e.kind
      when 'file' then select to_jsonb(f) into content from classmate.file_metadata f where id = e.record_id and status = 'active';
      when 'class_change' then select to_jsonb(c) into content from classmate.class_changes c where id = e.record_id;
      when 'semester' then select to_jsonb(s) into content from classmate.semesters s where id = e.record_id;
      when 'app_update' then select to_jsonb(a) into content from classmate.app_releases a where id = e.record_id;
      else return null;
    end case;
  end if;

  if content is null or (e.kind <> 'app_update' and (content->>'batch_id' is distinct from e.batch_id::text or (e.kind <> 'semester' and content->>'department_id' is distinct from e.department_id::text))) then
    return null;
  end if;

  return jsonb_build_object(
    'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = j.token_hash and profile_id = j.profile_id),
    'batch_id', coalesce(e.batch_id, (select batch_id from classmate.profiles where id = j.profile_id)),
    'title', left(coalesce(content->>'title', 'ClassMate update'), 200),
    'body', left(coalesce(content->>'body', 'New class update available'), 240),
    'failures', j.failures,
    'kind', e.kind,
    'record_id', e.record_id::text,
    'event_id', e.id::text
  );
end;
$$;

commit;
