-- 202611060004_institutions_multitenant_foundation.sql
-- Enterprise Multi-Tenant Foundation: Universities, Departments, Dynamic Auth, and Scoped Directories

-- 1. Create classmate.institutions table
CREATE TABLE IF NOT EXISTS classmate.institutions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    short_name TEXT NOT NULL,
    slug TEXT UNIQUE NOT NULL,
    email_domain TEXT UNIQUE NOT NULL,
    weekend_days INT[] NOT NULL DEFAULT '{5,6}', -- ISO day numbers: 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun
    has_bus_transit BOOLEAN NOT NULL DEFAULT false,
    has_academic_calendar BOOLEAN NOT NULL DEFAULT false,
    student_id_pattern TEXT,
    support_contact_url TEXT DEFAULT 'https://facebook.com/shuaibuddowla',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable RLS
ALTER TABLE classmate.institutions ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies 
    WHERE schemaname = 'classmate' AND tablename = 'institutions' AND policyname = 'institutions_public_read'
  ) THEN
    CREATE POLICY institutions_public_read ON classmate.institutions
      FOR SELECT USING (is_active = true);
  END IF;
END $$;

-- 2. Seed initial tenants (MBSTU & University of Rajshahi)
INSERT INTO classmate.institutions (
    id, name, short_name, slug, email_domain, weekend_days, has_bus_transit, has_academic_calendar, student_id_pattern, support_contact_url
) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Mawlana Bhashani Science and Technology University', 'MBSTU', 'mbstu', 'mbstu.ac.bd', '{4,5}', true, true, '^[A-Za-z]{2}[0-9]{5}$', 'https://facebook.com/shuaibuddowla'),
    ('a0000000-0000-0000-0000-000000000002', 'University of Rajshahi', 'RU', 'ru', 'ru.ac.bd', '{5,6}', false, false, '^[0-9]{10}$', 'https://facebook.com/shuaibuddowla')
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    short_name = EXCLUDED.short_name,
    email_domain = EXCLUDED.email_domain,
    weekend_days = EXCLUDED.weekend_days,
    has_bus_transit = EXCLUDED.has_bus_transit,
    has_academic_calendar = EXCLUDED.has_academic_calendar,
    student_id_pattern = EXCLUDED.student_id_pattern,
    support_contact_url = EXCLUDED.support_contact_url,
    updated_at = now();

-- 3. Add institution_id to classmate.departments
ALTER TABLE classmate.departments 
ADD COLUMN IF NOT EXISTS institution_id UUID REFERENCES classmate.institutions(id);

-- Backfill MBSTU departments
UPDATE classmate.departments
SET institution_id = 'a0000000-0000-0000-0000-000000000001'
WHERE code <> 'fisheries' AND (institution_id IS NULL OR institution_id <> 'a0000000-0000-0000-0000-000000000001');

-- Backfill RU departments
UPDATE classmate.departments
SET institution_id = 'a0000000-0000-0000-0000-000000000002'
WHERE code = 'fisheries' AND (institution_id IS NULL OR institution_id <> 'a0000000-0000-0000-0000-000000000002');

-- 4. Add institution_id to classmate.bus_schedules
ALTER TABLE classmate.bus_schedules 
ADD COLUMN IF NOT EXISTS institution_id UUID REFERENCES classmate.institutions(id);

-- Backfill existing bus schedules to MBSTU
UPDATE classmate.bus_schedules
SET institution_id = 'a0000000-0000-0000-0000-000000000001'
WHERE institution_id IS NULL;

-- 5. Add institution_id to classmate.profiles
ALTER TABLE classmate.profiles
ADD COLUMN IF NOT EXISTS institution_id UUID REFERENCES classmate.institutions(id);

-- Backfill profiles
UPDATE classmate.profiles p
SET institution_id = d.institution_id
FROM classmate.departments d
WHERE p.department_id = d.id AND p.institution_id IS NULL;

UPDATE classmate.profiles p
SET institution_id = 'a0000000-0000-0000-0000-000000000002'
WHERE p.institution_id IS NULL AND lower(p.email) LIKE '%@ru.ac.bd';

UPDATE classmate.profiles p
SET institution_id = 'a0000000-0000-0000-0000-000000000001'
WHERE p.institution_id IS NULL AND lower(p.email) LIKE '%@mbstu.ac.bd';

-- 6. Dynamic Auth Pre-Signup Hook (Zero Hardcoded Domains)
CREATE OR REPLACE FUNCTION classmate.before_user_created(event jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_email text;
  v_domain text;
  v_is_univ_domain boolean;
BEGIN
  v_email := lower(trim(event->'record'->>'email'));

  IF v_email IS NULL OR v_email = '' THEN
    RAISE EXCEPTION 'Email is required' USING errcode = '42501';
  END IF;

  v_domain := lower(split_part(v_email, '@', 2));

  -- Check dynamically if domain matches any active institution
  SELECT EXISTS (
    SELECT 1 FROM classmate.institutions 
    WHERE (email_domain = v_domain OR v_domain LIKE '%.' || email_domain)
      AND is_active = true
  ) INTO v_is_univ_domain;

  IF NOT v_is_univ_domain AND NOT (
    v_email ~ '^[^[:space:]@]+@gmail[.]com$' 
    AND EXISTS (
      SELECT 1 FROM classmate.teacher_allowlist 
      WHERE email = v_email AND active = true
    )
  ) THEN
    RAISE EXCEPTION 'Signing in failed: 403: Use a recognized university Google account or an admin-approved teacher Gmail.' USING errcode = '42501';
  END IF;

  RETURN event;
END;
$$;

-- 7. Multi-Tenant Teacher Directory RPC
CREATE OR REPLACE FUNCTION classmate.batch_teachers_directory(target_batch uuid)
 RETURNS TABLE(id uuid, department_code text, department_name text, full_name text, email text, designation text, phone text, profile_url text, source_checked_at date, courses text[])
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_target_institution_id uuid;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode = '42501';
  end if;

  select d.institution_id into v_target_institution_id
  from classmate.batches b
  join classmate.departments d on d.id = b.department_id
  where b.id = target_batch;

  return query
    select
      t.id,
      d.code as department_code,
      d.name as department_name,
      t.full_name,
      t.email,
      t.designation,
      t.phone,
      t.profile_url,
      t.source_checked_at,
      coalesce(
        array_remove(
          array_agg(distinct coalesce(sc.code_override, c.course_code) order by coalesce(sc.code_override, c.course_code)),
          null
        ),
        array[]::text[]
      ) as courses
    from classmate.teacher_directory t
    join classmate.departments d on d.id = t.department_id
    left join classmate.semester_courses sc on sc.teacher_record_id = t.id
    left join classmate.semesters s on s.id = sc.semester_id and s.batch_id = target_batch and s.status = 'active'
    left join classmate.courses c on c.id = sc.course_id
    where t.full_name !~* '^(CSE batch|Test|Dummy)'
      and (
        d.institution_id = v_target_institution_id
        or (v_target_institution_id is null and d.institution_id = (select inst.id from classmate.institutions inst where inst.slug = 'mbstu'))
      )
    group by
      t.id,
      d.code,
      d.name,
      t.full_name,
      t.email,
      t.designation,
      t.phone,
      t.profile_url,
      t.source_checked_at
    order by
      (d.institution_id = v_target_institution_id) desc,
      d.name,
      lower(t.full_name);
end;
$function$;

-- 8. Institution Config RPC for Client Apps
CREATE OR REPLACE FUNCTION classmate.get_my_institution()
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
  v_user_id uuid := auth.uid();
  v_email text;
  v_domain text;
  v_inst classmate.institutions%rowtype;
  v_dept_id uuid;
  v_profile_inst_id uuid;
begin
  if v_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;

  select email, department_id, institution_id into v_email, v_dept_id, v_profile_inst_id
  from classmate.profiles
  where id = v_user_id;

  -- Priority 1: Direct profile institution_id
  if v_profile_inst_id is not null then
    select * into v_inst from classmate.institutions where id = v_profile_inst_id and is_active;
  end if;

  -- Priority 2: Institution via department_id
  if v_inst.id is null and v_dept_id is not null then
    select i.* into v_inst
    from classmate.departments d
    join classmate.institutions i on i.id = d.institution_id
    where d.id = v_dept_id and i.is_active;
  end if;

  -- Priority 3: Lookup by email domain
  if v_inst.id is null and v_email is not null then
    v_domain := lower(split_part(v_email, '@', 2));
    select * into v_inst
    from classmate.institutions
    where (email_domain = v_domain or v_domain like '%.' || email_domain) and is_active
    limit 1;
  end if;

  -- Fallback default (MBSTU)
  if v_inst.id is null then
    select * into v_inst from classmate.institutions where slug = 'mbstu';
  end if;

  return jsonb_build_object(
    'id', v_inst.id,
    'name', v_inst.name,
    'short_name', v_inst.short_name,
    'slug', v_inst.slug,
    'email_domain', v_inst.email_domain,
    'weekend_days', v_inst.weekend_days,
    'has_bus_transit', v_inst.has_bus_transit,
    'has_academic_calendar', v_inst.has_academic_calendar,
    'support_contact_url', coalesce(v_inst.support_contact_url, 'https://facebook.com/shuaibuddowla')
  );
end;
$function$;
