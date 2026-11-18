-- ============================================================================
-- Migration: 202611130001_fix_can_manage_batch_fund_permissions.sql
-- Fix can_manage_batch_fund to align with can_manage_batch:
-- Allows CRs (matching batch_id or cr_batch_id), institution Admins, and Owners.
-- ============================================================================

create or replace function classmate.can_manage_batch_fund(target_batch uuid)
returns boolean
language sql
stable security definer
set search_path to '' as $$
  select exists(
    select 1 from classmate.profiles p
    join classmate.batches b on b.id = target_batch
    join classmate.departments d on d.id = b.department_id
    where p.id = auth.uid()
      and p.verification_status = 'active'
      and (
        (p.role = 'student' and p.is_cr and (p.cr_batch_id = target_batch or p.batch_id = target_batch))
        or (p.role = 'admin' and (p.institution_id is null or p.institution_id = d.institution_id))
        or classmate.is_owner()
      )
  );
$$;

revoke all on function classmate.can_manage_batch_fund(uuid) from public, anon;
grant execute on function classmate.can_manage_batch_fund(uuid) to authenticated, service_role;
