-- ============================================================================
-- Migration: 202611040002_harden_student_card_lookup.sql
-- Description:
-- Harden student card lookup RPC.
-- Protect confidential student attendance records: only expose attendance_percentage
-- to authenticated callers (the student themselves, CR of their batch, teacher, or admin/owner).
-- Anonymous/public visitors scanning lost cards retain the name, roll, and return
-- contact actions, but cannot access private academic attendance metrics.
-- ============================================================================

begin;

create or replace function classmate.lookup_student_by_card(
  p_card_uid text
)
returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare
  v_clean_uid text := lower(trim(replace(coalesce(p_card_uid, ''), ':', '')));
  v_profile record;
  v_attendance_pct numeric := null;
  v_caller uuid := auth.uid();
  v_can_see_attendance boolean := false;
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

  -- Verify if caller is authorized to view private academic attendance
  if v_caller is not null then
    if v_caller = v_profile.id or classmate.is_owner() then
      v_can_see_attendance := true;
    else
      v_can_see_attendance := exists (
        select 1 from classmate.profiles cp
        where cp.id = v_caller
          and (
            cp.role in ('admin', 'teacher')
            or (cp.is_cr = true and cp.batch_id = v_profile.batch_id)
          )
      );
    end if;
  end if;

  -- Only compute attendance percentage for authorized viewers
  if v_can_see_attendance then
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
  end if;

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

revoke all on function classmate.lookup_student_by_card(text) from public;
grant execute on function classmate.lookup_student_by_card(text) to anon, authenticated, service_role;

commit;
