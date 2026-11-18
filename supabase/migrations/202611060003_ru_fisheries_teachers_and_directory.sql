-- 202611060003_ru_fisheries_teachers_and_directory.sql
-- Seed Rajshahi University Faculty of Fisheries teachers into classmate.teacher_directory
-- and update classmate.batch_teachers_directory to filter teachers by batch department

DO $$
DECLARE
  v_fisheries_dept_id uuid;
BEGIN
  SELECT id INTO v_fisheries_dept_id
  FROM classmate.departments
  WHERE code = 'fisheries';

  IF v_fisheries_dept_id IS NULL THEN
    RAISE EXCEPTION 'Fisheries department not found';
  END IF;

  -- Insert or update RU Fisheries faculty members
  INSERT INTO classmate.teacher_directory (
    department_id,
    full_name,
    email,
    designation,
    phone,
    profile_url,
    source_checked_at
  ) VALUES
    (v_fisheries_dept_id, 'Prof. Saleha Jasmine', 'chairman.fisheries@ru.ac.bd', 'Professor & Chairman', '+880721711117', 'https://www.ru.ac.bd/fisheries/member/prof-saleha-jasmine/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Mostafizur Rahman Mondol', 'mostafiz@ru.ac.bd', 'Dean & Professor', '+8801712581153', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-mostafizur-rahman-mondol/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Yeamin Hossain', 'yeamin.fish@ru.ac.bd', 'Professor', '+8801711904799', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-yeamin-hossain/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Akhtar Hossain', 'hossainma@ru.ac.bd', 'Professor', '+8801711576135', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-akhtar-hossain/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Mahabubur Rahman', 'mmrr77@ru.ac.bd', 'Professor', '+8801712282245', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-mahabubur-rahman/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Istiaque Hossain', 'bitan@ru.ac.bd', 'Professor', '+8801711428383', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-istiaque-hossain/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. A. B. M. Mohsin', 'abmmohsin@ru.ac.bd', 'Professor', '+8801715424595', 'https://www.ru.ac.bd/fisheries/member/prof-dr-a-b-m-mohsin/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Fawzia Adib Flowra', 'florabd@yahoo.com', 'Professor', '+8801718228399', 'https://www.ru.ac.bd/fisheries/member/prof-dr-fawzia-adib-flowra/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. M. Manjurul Alam', 'manjurul@ru.ac.bd', 'Professor', '+8801712584128', 'https://www.ru.ac.bd/fisheries/member/prof-dr-m-manjurul-alam/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Tariqul Islam', 'tariqul.fish@ru.ac.bd', 'Professor', '+8801715049386', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-tariqul-islam/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Delwer Hossain', 'delwer71@ru.ac.bd', 'Professor', '+8801712297171', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-delwer-hossain/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Abdur Razzaq Joadder', 'raz2006_ru@yahoo.com', 'Professor', '+8801712584989', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-abdur-razzaq-joadder/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Al-Amin Sarker', 'maasarker@ru.ac.bd', 'Professor', '+8801717388488', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-al-amin-sarker/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Syeda Nusrat Jahan', 'nusratru@ru.ac.bd', 'Professor', '+8801715017282', 'https://www.ru.ac.bd/fisheries/member/prof-dr-syeda-nusrat-jahan/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Dr. Shams M. Galib', 'thegalib@ru.ac.bd', 'Associate Professor', '+8801717070566', 'https://www.ru.ac.bd/fisheries/member/dr-shams-m-galib/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Abdus Samad', 'samad1419@yahoo.com', 'Professor', '+8801716388477', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-abdus-samad/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Muhammad Afzal Hussain', 'afzal_fish@ru.ac.bd', 'Professor', '+8801712581200', 'https://www.ru.ac.bd/fisheries/member/prof-dr-muhammad-afzal-hussain/', CURRENT_DATE),
    (v_fisheries_dept_id, 'Prof. Dr. Md. Abu Sayed Jewel', 'jewel_ru@yahoo.com', 'Professor', '+8801712581300', 'https://www.ru.ac.bd/fisheries/member/prof-dr-md-abu-sayed-jewel/', CURRENT_DATE)
  ON CONFLICT (department_id, email) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    designation = EXCLUDED.designation,
    phone = EXCLUDED.phone,
    profile_url = EXCLUDED.profile_url,
    source_checked_at = EXCLUDED.source_checked_at,
    updated_at = NOW();

END $$;

-- Update classmate.batch_teachers_directory to filter teachers by batch department
CREATE OR REPLACE FUNCTION classmate.batch_teachers_directory(target_batch uuid)
 RETURNS TABLE(id uuid, department_code text, department_name text, full_name text, email text, designation text, phone text, profile_url text, source_checked_at date, courses text[])
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_dept_code text;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) then
    raise exception 'Batch access denied' using errcode = '42501';
  end if;

  select d.code into v_dept_code
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
        case
          when v_dept_code = 'fisheries' then d.code = 'fisheries'
          else d.code <> 'fisheries'
        end
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
      (d.code = coalesce(v_dept_code, 'cse')) desc,
      d.name,
      lower(t.full_name);
end;
$function$;
