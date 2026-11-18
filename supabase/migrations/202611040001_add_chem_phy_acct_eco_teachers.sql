-- Migration: 202611040001_add_chem_phy_acct_eco_teachers.sql
-- Populates Chemistry, Physics, Accounting, and Economics faculty members into classmate.teacher_directory

-- 1. Ensure departments exist
INSERT INTO classmate.departments (code, name, is_active)
VALUES
  ('acct', 'Accounting', false),
  ('eco', 'Economics', false)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name;

DO $$
DECLARE
  v_chem_id uuid;
  v_phy_id uuid;
  v_acct_id uuid;
  v_eco_id uuid;
BEGIN
  SELECT id INTO v_chem_id FROM classmate.departments WHERE code = 'chem';
  SELECT id INTO v_phy_id FROM classmate.departments WHERE code = 'phy';
  SELECT id INTO v_acct_id FROM classmate.departments WHERE code = 'acct';
  SELECT id INTO v_eco_id FROM classmate.departments WHERE code = 'eco';

  -- 2. Correct department_id for existing cross-department teachers previously categorized under cse
  UPDATE classmate.teacher_directory
  SET department_id = v_chem_id
  WHERE email = 'pijushroy.chem@mbstu.ac.bd';

  UPDATE classmate.teacher_directory
  SET department_id = v_phy_id
  WHERE email IN ('tazul@mbstu.ac.bd', 'shariful.phy@mbstu.ac.bd');

  UPDATE classmate.teacher_directory
  SET department_id = v_acct_id
  WHERE email = 'makuddus@mbstu.ac.bd';

  UPDATE classmate.teacher_directory
  SET department_id = v_eco_id
  WHERE email = 'mehedihasan.econ43@mbstu.ac.bd';

  -- 3. Update existing MD. ASHRAFUL ALAM who had NULL email
  UPDATE classmate.teacher_directory
  SET email = 'ashraf.rubd@gmail.com',
      full_name = 'Md. Ashraful Alam',
      designation = 'Associate Professor',
      phone = '+880 1884774326',
      profile_url = 'https://phy.mbstu.ac.bd/teacher/md-ashraful-alam',
      source_checked_at = CURRENT_DATE
  WHERE id = '571bd1ae-78ea-4111-9de4-1d42fe4634f9';

  -- 4. Upsert all teachers from Chemistry, Physics, Accounting, Economics
  -- CHEM Teachers (18)
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Mohammad Idrish Ali', 'idrish@mbstu.ac.bd', 'Professor and Chairman', '+88-01818243031', 'https://chem.mbstu.ac.bd/teacher/dr-mohammad-idrish-ali', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Mohammad Khademul Islam', 'mkislam@mbstu.ac.bd', 'Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/dr-mohammad-khademul-islam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Md. Abdur Rahaman', 'md.arch27@mbstu.ac.bd', 'Professor', '+880 1842 564 310', 'https://chem.mbstu.ac.bd/teacher/dr-md-abdur-rahaman', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Umme Salma', 'ummesalma@mbstu.ac.bd', 'Professor', '+8801348970306', 'https://chem.mbstu.ac.bd/teacher/dr-umme-salma', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Muhammad Abul Kashem Liton', 'litonchem@mbstu.ac.bd', 'Associate Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-muhammad-abul-kashem-liton', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Ashish Kumar Sarkar', 'ashis@mbstu.ac.bd', 'Associate Professor', '+8801719897953', 'https://chem.mbstu.ac.bd/teacher/dr-ashish-kumar-sarkar', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Arjun Chandra Bhowmik', 'bhowmick_mbstu@yahoo.com', 'Associate Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-arjun-chandra-bhowmik', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Md. Abu Rashed', 'abu.rashed@mbstu.ac.bd', 'Associate Professor', '+8801738987088', 'https://chem.mbstu.ac.bd/teacher/dr-md-abu-rashed', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Sharmin Sultana', 'sultana_chem@mbstu.ac.bd', 'Associate Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/dr-sharmin-sultana', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Pijush Kanti Roy', 'pijushroy.chem@mbstu.ac.bd', 'Associate Professor', '01717977907', 'https://chem.mbstu.ac.bd/teacher/dr-pijush-kanti-roy', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Dr. Rashedul Islam Ripon', 'riponchem@mbstu.ac.bd', 'Associate Professor', '+8801749959724', 'https://chem.mbstu.ac.bd/teacher/dr-rashedul-islam-ripon-d-rasedul-islam-ripn', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Md. Nuruzzaman', 'nuruzzaman.chem@mbstu.ac.bd', 'Associate Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-md-nuruzzaman-1', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Md. Masud Alam', 'masud.chem@mbstu.ac.bd', 'Associate Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-md-masud-alam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Sabrina Helen', 'sabrina01@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/sabrina-helen', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Md. Delwar Hossain', 'delwar.ju689@gmail.com', 'Assistant Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-md-delwar-hossain', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mr. Md. Shalauddin', 'shalauddin.mbstu@gmail.com', 'Assistant Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mr-md-shalauddin', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Mukta Das', 'mukta@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://chem.mbstu.ac.bd/teacher/mukta-das', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_chem_id, 'Md. Jobayer Ahmed', 'jobayerahmed@mbstu.ac.bd', 'Lecturer', '01791587440', 'https://chem.mbstu.ac.bd/teacher/md-jobayer-ahmed', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;

  -- PHY Teachers (20)
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Md. Anwar Hossain', 'anwar647@mbstu.ac.bd', 'Professor', '+8801718407647', 'https://phy.mbstu.ac.bd/teacher/dr-md-anwar-hossain', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Md. Masum Haider', 'masum.phy@mbstu.ac.bd', 'Professor', '+8801812-388880', 'https://phy.mbstu.ac.bd/teacher/dr-md-masum-haider', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Md. Moniruzzaman', 'monir_m17@yahoo.com', 'Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/dr-md-moniruzzaman', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Md. Didarul Islam Bhuyan', 'didar_bhuiyan@mbstu.ac.bd', 'Professor', '+8801719660952', 'https://phy.mbstu.ac.bd/teacher/dr-md-didarul-islam-bhuyan', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Obaydur Rahman', 'orahmanphy@mbstu.ac.bd', 'Professor', '+8801511-976026', 'https://phy.mbstu.ac.bd/teacher/dr-obaydur-rahman', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Jobaidul Islam', 'ijobaidul@mbstu.ac.bd', 'Associate Professor', '+8801914-990934', 'https://phy.mbstu.ac.bd/teacher/jobaidul-islam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Khondokar Nazmus Sakib', 'sakib_58@yahoo.com', 'Associate Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/khondokar-nazmus-sakib', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Mohiuddin Tasnim', 'mtasnim89@gmail.com', 'Associate Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/md-mohiuddin-tasnim', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Sumal Chandra', 'sumalchandra@yahoo.com', 'Associate Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/dr-sumal-chandra', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Dr. Md. Rasadujjaman', 'rasadphy@mbstu.ac.bd', 'Associate Professor', '+8801779677700', 'https://phy.mbstu.ac.bd/teacher/dr-md-rasadujjaman', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Ashraful Alam', 'ashraf.rubd@gmail.com', 'Associate Professor', '+880 1884774326', 'https://phy.mbstu.ac.bd/teacher/md-ashraful-alam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Umme Habiba', 'bintehabib040@gmail.com', 'Associate Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/umme-habiba', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Abdur Razzaq', 'razzaq@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/md-abdur-razzaq', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Ariful Alam', 'arifulalam@mbstu.ac.bd', 'Associate Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/ariful-alam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Afiya Akter Piya', 'afiya@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/afiya-akter-piya', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Nasir Uddin', 'nasiruddin@mbstu.ac.bd', 'Assistant Professor', '+8801756539939', 'https://phy.mbstu.ac.bd/teacher/md-nasir-uddin', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Siraj Ud Daula Shamim', 'sdshamim@mbstu.ac.bd', 'Assistant Professor', '+8801761072293', 'https://phy.mbstu.ac.bd/teacher/md-siraj-ud-daula-shamim', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Khairunnahar Munny', 'khairunnaharmunny@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://phy.mbstu.ac.bd/teacher/khairunnahar-munny', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Tazul Islam', 'tazul@mbstu.ac.bd', 'Assistant Professor', '+8801883242791', 'https://phy.mbstu.ac.bd/teacher/tazul-islam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_phy_id, 'Md. Shariful Alam', 'shariful.phy@mbstu.ac.bd', 'Lecturer', '+8801795677870', 'https://phy.mbstu.ac.bd/teacher/md-shariful-alam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;

  -- ACCT Teachers (11)
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Dr. Syed Moudud-Ul-Huq', 'moudud_cu7@mbstu.ac.bd', 'Professor', NULL, 'https://acct.mbstu.ac.bd/teacher/dr-syed-moudud-ul-huq', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Dr. Md. Rostam Ali', 'rostam@mbstu.ac.bd', 'Professor', '+8801722678486', 'https://acct.mbstu.ac.bd/teacher/dr-md-rostam-ali', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Dr. Md. Nazmul Islam', 'nazmulaccounting@mbstu.ac.bd', 'Professor', '+8801723105837', 'https://acct.mbstu.ac.bd/teacher/dr-md-nazmul-islam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Tanmay Biswas', 'tbiswas@mbstu.ac.bd', 'Associate Professor', '+8801734568901', 'https://acct.mbstu.ac.bd/teacher/tanmay-biswas', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Ms. Reshma Pervin Lima', 'reshma.pervin973@gmail.com', 'Associate Professor', NULL, 'https://acct.mbstu.ac.bd/teacher/ms-reshma-pervin-lima', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Brishti Chakraborty', 'brishti.acct@mbstu.ac.bd', 'Assistant Professor', '+8801734488973', 'https://acct.mbstu.ac.bd/teacher/brishti-chakraborty', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Md. Al Amin', 'talukdaramin@mbstu.ac.bd', 'Assistant Professor', NULL, 'https://acct.mbstu.ac.bd/teacher/md-al-amin', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Md. Abdul Halim', 'halim@mbstu.ac.bd', 'Lecturer', '+8801732-054166', 'https://acct.mbstu.ac.bd/teacher/md-abdul-halim', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Dr. Md. Abdul Kuddus', 'makuddus@mbstu.ac.bd', 'Lecturer', '+8801717854104', 'https://acct.mbstu.ac.bd/teacher/dr-md-abdul-kuddus', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Shohana Islam Sumona', 'shohanaislam@mbstu.ac.bd', 'Lecturer', '01856936776', 'https://acct.mbstu.ac.bd/teacher/shohana-islam-sumona', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_acct_id, 'Afsana Sima', 'afsanasima@mbstu.ac.bd', 'Lecturer', NULL, 'https://acct.mbstu.ac.bd/teacher/afsana-sima', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;

  -- ECO Teachers (16)
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dr. Sanjoy Kumar Saha', 'saha.sk2021@mbstu.ac.bd', 'Professor', '+8801712659062', 'https://eco.mbstu.ac.bd/teacher/dr-sanjoy-kumar-saha', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dr. Md. Nazmus Sadekin', 'sadekin@mbstu.ac.bd', 'Professor and Chairman', '+8801776196547', 'https://eco.mbstu.ac.bd/teacher/dr-md-nazmus-sadekin-shaon', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dr. Muhammad Rabiul Islam Liton', 'rabiul388@gmail.com', 'Professor', '+8801729992499', 'https://eco.mbstu.ac.bd/teacher/dr-muhammad-rabiul-islam-liton', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dr. Subrata Saha', 'subrataeco2015@mbstu.ac.bd', 'Associate Professor', '01736222987', 'https://eco.mbstu.ac.bd/teacher/dr-subrata-saha', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dr. Al Amin Al Abbasi', 'abbasi_eco@mbstu.ac.bd', 'Associate Professor', '01722197882', 'https://eco.mbstu.ac.bd/teacher/dr-al-amin-al-abbasi', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Ms. Roksana Akhter', 'roksanaakhter@mbstu.ac.bd', 'Associate Professor', '01991811317', 'https://eco.mbstu.ac.bd/teacher/ms-roksana-akhter', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Sabiha Binta Hasan', 'sabihaeco@mbstu.ac.bd', 'Associate Professor', '01772234823', 'https://eco.mbstu.ac.bd/teacher/sabiha-binta-hasan', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Dipti Bhowmik', 'bhowmikdipti@mbstu.ac.bd', 'Assistant Professor', '01671000905', 'https://eco.mbstu.ac.bd/teacher/dipti-bhowmik', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Subroto Dey', 'subrotodey@mbstu.ac.bd', 'Assistant Professor', '01703994527', 'https://eco.mbstu.ac.bd/teacher/subroto-dey', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Shakil Mahmood Shaon', 'shaoneco@mbstu.ac.bd', 'Assistant Professor', '01712793456', 'https://eco.mbstu.ac.bd/teacher/shakil-mahmood-shaon', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Md. Tuhin Ahmed', 'tuhin.ahmed@mbstu.ac.bd', 'Lecturer', '+8801764775266', 'https://eco.mbstu.ac.bd/teacher/md-tuhin-ahmed', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Mehedi Hasan', 'mehedihasan.econ43@mbstu.ac.bd', 'Lecturer', '+8801687214847', 'https://eco.mbstu.ac.bd/teacher/mehedi-hasan', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Jannat Rahat Jui', 'jannat.jui@mbstu.ac.bd', 'Lecturer', '01775974279', 'https://eco.mbstu.ac.bd/teacher/jannat-rahat-jui', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Md. Ariful Islam', 'ariful.islam@mbstu.ac.bd', 'Lecturer', '01915919574', 'https://eco.mbstu.ac.bd/teacher/md-ariful-islam', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Ashiqur Rahman', 'ashiqur005@mbstu.ac.bd', 'Lecturer', '+8801677546262', 'https://eco.mbstu.ac.bd/teacher/ashiqur-rahman', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;
  INSERT INTO classmate.teacher_directory (department_id, full_name, email, designation, phone, profile_url, source_checked_at)
  VALUES (v_eco_id, 'Md. Moniruzzaman Muzib', 'zamanmuzib@mbstu.ac.bd', 'Associate Professor (On Leave)', '+8801712719030', 'https://eco.mbstu.ac.bd/teacher/md-moniruzzaman-muzib', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = COALESCE(EXCLUDED.designation, classmate.teacher_directory.designation),
    phone = COALESCE(EXCLUDED.phone, classmate.teacher_directory.phone),
    profile_url = COALESCE(EXCLUDED.profile_url, classmate.teacher_directory.profile_url),
    source_checked_at = CURRENT_DATE;

END $$;
