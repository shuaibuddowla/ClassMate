-- Migration: Support both parameter naming conventions for enroll_student_rfid
-- Allows calling via (p_student_id, p_card_uid) or (target_student, target_card_uid)

begin;

drop function if exists classmate.enroll_student_rfid(uuid, text);

create or replace function classmate.enroll_student_rfid(
  p_student_id uuid default null,
  p_card_uid text default null,
  target_student uuid default null,
  target_card_uid text default null
)
returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  v_student_id uuid := coalesce(p_student_id, target_student);
  v_raw_uid text := coalesce(p_card_uid, target_card_uid);
  v_clean_uid text;
  v_existing record;
begin
  if not (
    classmate.is_owner()
    or exists (
      select 1 from classmate.profiles
      where id = auth.uid() and (role in ('admin', 'teacher') or is_cr = true)
    )
  ) then
    raise exception 'Permission denied to enroll RFID cards' using errcode='42501';
  end if;

  if v_student_id is null then
    raise exception 'Student ID is required' using errcode='22023';
  end if;

  v_clean_uid := lower(trim(replace(coalesce(v_raw_uid, ''), ':', '')));
  if v_clean_uid = '' or v_clean_uid is null then
    raise exception 'Invalid card UID' using errcode='22023';
  end if;

  -- Check if already assigned to another student
  select id, full_name, student_id into v_existing
  from classmate.profiles
  where lower(replace(coalesce(rfid_card_uid, ''), ':', '')) = v_clean_uid
    and id <> v_student_id;

  if found then
    raise exception 'Card % is already enrolled to % (%)', v_clean_uid, v_existing.full_name, v_existing.student_id using errcode='23505';
  end if;

  update classmate.profiles
  set rfid_card_uid = v_clean_uid
  where id = v_student_id;

  -- Mark pending unregistered scans as resolved
  update classmate.unregistered_rfid_scans
  set resolved = true, resolved_at = now(), resolved_by = auth.uid()
  where lower(replace(card_uid, ':', '')) = v_clean_uid
    and resolved = false;

  return jsonb_build_object(
    'success', true,
    'student_id', v_student_id,
    'card_uid', v_clean_uid
  );
end $$;

grant execute on function classmate.enroll_student_rfid(uuid, text, uuid, text) to authenticated;

commit;
