-- ============================================================================
-- Migration: RFID Card Restart and Public Lookup for Lost & Found Cards
-- ============================================================================

begin;

-- 1. RPC: Restart / Unlink RFID card for a student
create or replace function classmate.restart_student_rfid(
  p_student_id uuid default null
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  v_caller uuid := auth.uid();
  v_target uuid := coalesce(p_student_id, v_caller);
  v_target_profile record;
begin
  if v_caller is null then
    raise exception 'Not authenticated' using errcode='42501';
  end if;

  select id, full_name, student_id, rfid_card_uid
  into v_target_profile
  from classmate.profiles
  where id = v_target;

  if not found then
    raise exception 'Student profile not found' using errcode='42704';
  end if;

  -- Allowed if restarting own card OR if caller is admin/CR/owner
  if v_target <> v_caller and not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = v_caller and (role in ('admin', 'teacher') or is_cr = true)
    )
  ) then
    raise exception 'Permission denied to restart this card' using errcode='42501';
  end if;

  update classmate.profiles
  set rfid_card_uid = null
  where id = v_target;

  return jsonb_build_object(
    'success', true,
    'student_id', v_target,
    'full_name', v_target_profile.full_name,
    'roll', coalesce(v_target_profile.student_id, ''),
    'previous_card_uid', v_target_profile.rfid_card_uid
  );
end $$;

-- 2. RPC: Public lookup of a student by RFID/NFC card UID (for lost cards)
create or replace function classmate.lookup_student_by_card(
  p_card_uid text
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  v_clean_uid text := lower(trim(replace(coalesce(p_card_uid, ''), ':', '')));
  v_profile record;
  v_attendance_pct numeric := null;
begin
  if v_clean_uid = '' or v_clean_uid is null then
    return jsonb_build_object('found', false, 'message', 'Invalid card UID');
  end if;

  select 
    p.id,
    p.full_name,
    coalesce(p.student_id, '') as student_id,
    p.batch_id,
    p.mobile_number,
    p.blood_group,
    p.rfid_card_uid,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') end as avatar_url,
    coalesce(d.code || ' ' || b.batch_number::text, 'MBSTU Student') as batch_label,
    coalesce(d.name, 'MBSTU') as department_label
  into v_profile
  from classmate.profiles p
  left join auth.users u on u.id = p.id
  left join classmate.batches b on b.id = p.batch_id
  left join classmate.departments d on d.id = b.department_id
  where lower(replace(coalesce(p.rfid_card_uid, ''), ':', '')) = v_clean_uid;

  if not found then
    return jsonb_build_object(
      'found', false,
      'card_uid', v_clean_uid,
      'message', 'Card is not registered to any student'
    );
  end if;

  -- Calculate overall attendance percentage if records exist
  select 
    case when count(*) > 0 then
      round((count(case when ar.marked_at is not null then 1 end)::numeric / count(*)::numeric) * 100.0, 1)
    else null end
  into v_attendance_pct
  from classmate.attendance_sessions s
  join classmate.routine_slots rs on rs.id = s.routine_slot_id
  join classmate.semester_courses sc on sc.id = rs.semester_course_id
  join classmate.semesters sem on sem.id = sc.semester_id
  left join classmate.attendance_records ar on ar.session_id = s.id and ar.student_id = v_profile.id
  where sem.batch_id = v_profile.batch_id;

  return jsonb_build_object(
    'found', true,
    'student_id', v_profile.id,
    'full_name', v_profile.full_name,
    'roll', v_profile.student_id,
    'batch_name', coalesce(v_profile.batch_label, 'MBSTU Student'),
    'department_name', coalesce(v_profile.department_label, 'MBSTU'),
    'avatar_url', v_profile.avatar_url,
    'phone', coalesce(v_profile.mobile_number, ''),
    'blood_group', coalesce(v_profile.blood_group, ''),
    'card_uid', v_clean_uid,
    'attendance_percentage', v_attendance_pct
  );
end $$;

-- 3. Grants
revoke all on function classmate.restart_student_rfid(uuid) from public, anon;
grant execute on function classmate.restart_student_rfid(uuid) to authenticated;

revoke all on function classmate.lookup_student_by_card(text) from public;
grant execute on function classmate.lookup_student_by_card(text) to anon, authenticated, service_role;

commit;
