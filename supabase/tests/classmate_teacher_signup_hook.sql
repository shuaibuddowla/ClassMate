begin;
insert into classmate.teacher_allowlist(email,department_id,active)
select 'hook-approved-fixture@gmail.com',id,true from classmate.departments limit 1;
do $$
declare approved jsonb := '{"user":{"email":"hook-approved-fixture@gmail.com","app_metadata":{"provider":"google"}}}';
begin
 if classmate.before_user_created(approved) <> '{}'::jsonb then raise exception 'Approved Gmail blocked';end if;
 if classmate.before_user_created('{"user":{"email":"ce25045@mbstu.ac.bd","app_metadata":{"provider":"google"}}}') <> '{}'::jsonb then raise exception 'Student blocked';end if;
 if not classmate.before_user_created('{"user":{"email":"hook-unapproved-fixture@gmail.com","app_metadata":{"provider":"google"}}}') ? 'error' then raise exception 'Unapproved Gmail allowed';end if;
 if not classmate.before_user_created('{"user":{"email":"hook-approved-fixture@gmail.com","app_metadata":{"provider":"email"}}}') ? 'error' then raise exception 'Non-Google allowed';end if;
 update classmate.teacher_allowlist set active=false where email='hook-approved-fixture@gmail.com';
 if not classmate.before_user_created(approved) ? 'error' then raise exception 'Revoked Gmail allowed';end if;
end $$;
rollback;
