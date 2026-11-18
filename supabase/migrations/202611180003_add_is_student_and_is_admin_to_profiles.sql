-- Migration: Add is_student and is_admin generated columns to profiles
-- and standardize student-context queries (like batch_fund_payers) to use is_student

ALTER TABLE classmate.profiles 
ADD COLUMN IF NOT EXISTS is_student boolean GENERATED ALWAYS AS (role <> 'teacher'::classmate.user_role AND batch_id IS NOT NULL) STORED;

ALTER TABLE classmate.profiles 
ADD COLUMN IF NOT EXISTS is_admin boolean GENERATED ALWAYS AS (role = 'admin'::classmate.user_role) STORED;

CREATE OR REPLACE FUNCTION classmate.batch_fund_payers(
  target_batch uuid,
  target_collection text default null
)
RETURNS TABLE(
  profile_id uuid,
  full_name text,
  student_id text,
  total_paid numeric,
  payment_count bigint,
  last_paid_at date
)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE
  clean_col text := null;
BEGIN
  IF NOT classmate.is_active() OR NOT classmate.can_read_batch(target_batch) THEN
    RAISE EXCEPTION 'Batch fund access denied' USING errcode='42501';
  END IF;

  IF target_collection IS NOT NULL AND trim(target_collection) <> '' AND lower(trim(target_collection)) <> 'all' THEN
    clean_col := lower(trim(target_collection));
  END IF;

  RETURN QUERY
  SELECT 
    p.id AS profile_id,
    p.full_name,
    p.student_id,
    coalesce(sum(case when t.type = 'inflow' then t.amount else 0 end), 0)::numeric AS total_paid,
    count(t.id) filter (where t.type = 'inflow')::bigint AS payment_count,
    max(t.transacted_at) filter (where t.type = 'inflow') AS last_paid_at
  FROM classmate.profiles p
  LEFT JOIN classmate.batch_fund_transactions t 
    ON t.batch_id = target_batch 
   AND t.type = 'inflow'
   AND (
     clean_col IS NULL 
     OR lower(classmate.canonical_fund_title(t.title)) = clean_col
     OR position(clean_col IN lower(t.title)) > 0
   )
   AND (
     (t.student_profile_id IS NOT NULL AND t.student_profile_id = p.id)
     OR (t.student_profile_id IS NULL AND t.student_id IS NOT NULL AND t.student_id = p.student_id)
     OR (t.student_profile_id IS NULL AND t.student_name IS NOT NULL AND lower(trim(t.student_name)) = lower(trim(p.full_name)))
   )
  WHERE p.verification_status = 'active'
    AND p.is_student
    AND (p.batch_id = target_batch OR p.effective_batch_id = target_batch)
  GROUP BY p.id, p.full_name, p.student_id
  ORDER BY 
    CASE WHEN coalesce(sum(case when t.type = 'inflow' then t.amount else 0 end), 0) > 0 THEN 0 ELSE 1 END ASC,
    total_paid DESC,
    p.student_id ASC NULLS LAST,
    p.full_name ASC;
END;
$$;
