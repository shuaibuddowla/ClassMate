-- 1. Feature flags table for global and scoped toggles
CREATE TABLE IF NOT EXISTS classmate.feature_flags (
  key text PRIMARY KEY,
  is_enabled boolean NOT NULL DEFAULT false,
  target_batches uuid[] NOT NULL DEFAULT ARRAY['de0b3379-54a0-4275-a526-5970295c787e'::uuid],
  description text,
  metadata jsonb DEFAULT '{}'::jsonb,
  updated_at timestamptz DEFAULT now()
);

-- Seed attendance_system feature flag (scoped to CSE-22 initially)
INSERT INTO classmate.feature_flags (key, is_enabled, target_batches, description)
VALUES (
  'attendance_system',
  false,
  ARRAY['de0b3379-54a0-4275-a526-5970295c787e'::uuid],
  'Controls visibility of the Attendance System. When is_enabled=true, visible to all users. When false, only target_batches.'
)
ON CONFLICT (key) DO UPDATE 
SET target_batches = EXCLUDED.target_batches;

-- 2. Optional batch-level override column on batches
ALTER TABLE classmate.batches 
ADD COLUMN IF NOT EXISTS attendance_enabled boolean DEFAULT NULL;

-- 3. Evaluation function
CREATE OR REPLACE FUNCTION classmate.is_attendance_feature_enabled(target_batch uuid DEFAULT NULL)
RETURNS boolean
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $func$
DECLARE
  v_global_enabled boolean;
  v_allowed_batches uuid[];
  v_batch_override boolean;
BEGIN
  SELECT is_enabled, target_batches
  INTO v_global_enabled, v_allowed_batches
  FROM classmate.feature_flags
  WHERE key = 'attendance_system';

  IF coalesce(v_global_enabled, false) = true THEN
    RETURN true;
  END IF;

  IF target_batch IS NULL THEN
    RETURN false;
  END IF;

  IF target_batch = ANY(coalesce(v_allowed_batches, ARRAY[]::uuid[])) THEN
    RETURN true;
  END IF;

  SELECT (
    coalesce(b.attendance_enabled, false)
    OR (d.code = 'cse' AND b.batch_number = 22)
  ) INTO v_batch_override
  FROM classmate.batches b
  JOIN classmate.departments d ON d.id = b.department_id
  WHERE b.id = target_batch;

  RETURN coalesce(v_batch_override, false);
END;
$func$;

-- 4. Toggle RPC for App Owner
CREATE OR REPLACE FUNCTION classmate.toggle_attendance_feature(enabled boolean)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $func$
BEGIN
  IF NOT classmate.is_owner() THEN
    RAISE EXCEPTION 'Only app owners can toggle global feature flags' USING errcode = '42501';
  END IF;

  UPDATE classmate.feature_flags
  SET is_enabled = enabled, updated_at = now()
  WHERE key = 'attendance_system';

  RETURN enabled;
END;
$func$;

-- 5. Helper RPC to get attendance feature status
CREATE OR REPLACE FUNCTION classmate.get_attendance_feature_status(target_batch uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $func$
DECLARE
  v_global_enabled boolean;
  v_batch_enabled boolean;
BEGIN
  SELECT coalesce(is_enabled, false) INTO v_global_enabled
  FROM classmate.feature_flags
  WHERE key = 'attendance_system';

  v_batch_enabled := classmate.is_attendance_feature_enabled(target_batch);

  RETURN jsonb_build_object(
    'global_enabled', coalesce(v_global_enabled, false),
    'batch_enabled', v_batch_enabled,
    'target_batch', target_batch
  );
END;
$func$;

-- 6. Integrate attendance_enabled into available_batches
CREATE OR REPLACE FUNCTION classmate.available_batches()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  return coalesce((select jsonb_agg(to_jsonb(q)) from(select b.id,b.department_id,b.batch_number,b.academic_session,
    coalesce(b.calendar_in_timetable, true) as calendar_in_timetable,
    classmate.is_attendance_feature_enabled(b.id) as attendance_enabled,
    jsonb_build_object('code',d.code,'name',d.name) departments
  from classmate.batches b join classmate.departments d on d.id=b.department_id
  where b.is_active and classmate.can_read_batch(b.id) and (
    classmate.is_owner()
    or not exists(select 1 from classmate.profiles p where p.id=auth.uid() and p.role='teacher')
    or exists(
      select 1 from classmate.teacher_course_assignments a
      join classmate.semester_courses sc on sc.id=a.semester_course_id
      join classmate.semesters s on s.id=sc.semester_id
      where a.teacher_id=auth.uid() and a.active and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
    or exists(
      select 1 from classmate.semester_courses sc
      join classmate.semesters s on s.id=sc.semester_id
      join classmate.teacher_directory td on td.id=sc.teacher_record_id
      join classmate.profiles p on p.id=auth.uid()
      where (
        td.profile_id=auth.uid()
        or lower(td.email)=lower(p.email)
        or split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)
      ) and s.status in ('active', 'not_started') and s.batch_id=b.id
    )
  )
  order by d.code,b.batch_number)q),'[]'::jsonb);
end $function$;

-- Grant execution to authenticated & anon
GRANT EXECUTE ON FUNCTION classmate.is_attendance_feature_enabled(uuid) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION classmate.toggle_attendance_feature(boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION classmate.get_attendance_feature_status(uuid) TO authenticated, anon;
