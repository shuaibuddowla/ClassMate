-- 202611090001_fix_is_readd_student_overload_ambiguity.sql
-- Drops the obsolete 2-argument overload of classmate.is_readd_student(text, uuid)
-- so calls to classmate.is_readd_student(text, uuid) resolve unambiguously to
-- classmate.is_readd_student(p_student_id text, p_batch_id uuid, p_email text default null).

begin;

drop function if exists classmate.is_readd_student(text, uuid);

commit;
