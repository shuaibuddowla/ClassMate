-- Migration: Enhance batch_fund_summary with student metrics and user-specific stats
CREATE OR REPLACE FUNCTION classmate.can_manage_batch_fund(target_batch uuid)
RETURNS boolean
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT EXISTS (
    SELECT 1 FROM classmate.profiles p
    JOIN classmate.batches b ON b.id = target_batch
    JOIN classmate.departments d ON d.id = b.department_id
    WHERE p.id = auth.uid()
      AND p.verification_status = 'active'
      AND (
        (p.is_student AND p.is_cr AND (p.cr_batch_id = target_batch OR p.batch_id = target_batch))
        OR (p.is_admin AND (p.institution_id IS NULL OR p.institution_id = d.institution_id))
        OR classmate.is_owner()
      )
  );
$$;

CREATE OR REPLACE FUNCTION classmate.batch_fund_summary(target_batch uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  collected numeric(12, 2) := 0;
  spent numeric(12, 2) := 0;
  balance numeric(12, 2) := 0;
  tx_count int := 0;
  desc_text text := 'Used for mess, events, jersey, and other batch expenses.';
  user_is_cr boolean := false;
  dept_code text := '';
  batch_num int := 0;
  uni_code text := 'MBSTU';
  total_students int := 0;
  paid_students int := 0;
  my_paid numeric(12, 2) := 0;
  current_user_sid text := null;
BEGIN
  IF NOT classmate.is_active() OR NOT classmate.can_read_batch(target_batch) THEN
    RAISE EXCEPTION 'Batch fund access denied' USING errcode='42501';
  END IF;

  SELECT 
    coalesce(sum(case when type = 'inflow' then amount else 0 end), 0),
    coalesce(sum(case when type = 'outflow' then amount else 0 end), 0),
    count(*)
  INTO collected, spent, tx_count
  FROM classmate.batch_fund_transactions
  WHERE batch_id = target_batch;

  balance := collected - spent;

  SELECT coalesce(fund_description, desc_text) INTO desc_text
  FROM classmate.batch_fund_settings WHERE batch_id = target_batch;
  IF desc_text IS NULL OR trim(desc_text) = '' THEN
    desc_text := 'Used for mess, events, jersey, and other batch expenses.';
  END IF;

  user_is_cr := classmate.can_manage_batch_fund(target_batch);

  SELECT d.code, b.batch_number 
  INTO dept_code, batch_num
  FROM classmate.batches b
  JOIN classmate.departments d ON d.id = b.department_id
  WHERE b.id = target_batch;

  -- Count total students and distinct paid students in batch
  SELECT 
    count(distinct p.id),
    count(distinct p.id) filter (WHERE EXISTS (
      SELECT 1 FROM classmate.batch_fund_transactions t
      WHERE t.batch_id = target_batch AND t.type = 'inflow'
        AND (t.student_profile_id = p.id OR (t.student_id IS NOT NULL AND t.student_id = p.student_id))
    ))
  INTO total_students, paid_students
  FROM classmate.profiles p
  WHERE p.verification_status = 'active'
    AND p.is_student
    AND (p.batch_id = target_batch OR p.effective_batch_id = target_batch);

  -- Current user's contributions
  SELECT p.student_id INTO current_user_sid
  FROM classmate.profiles p WHERE p.id = auth.uid();

  SELECT coalesce(sum(t.amount), 0) INTO my_paid
  FROM classmate.batch_fund_transactions t
  WHERE t.batch_id = target_batch
    AND t.type = 'inflow'
    AND (t.student_profile_id = auth.uid() OR (current_user_sid IS NOT NULL AND t.student_id = current_user_sid));

  RETURN jsonb_build_object(
    'current_balance', balance,
    'total_collected', collected,
    'total_spent', spent,
    'transaction_count', tx_count,
    'fund_description', desc_text,
    'is_cr', user_is_cr,
    'batch_name', coalesce(dept_code || ' ' || batch_num, 'Batch Fund'),
    'university', uni_code,
    'total_students', total_students,
    'paid_students', paid_students,
    'unpaid_students', greatest(0, total_students - paid_students),
    'my_total_paid', my_paid
  );
END;
$$;
