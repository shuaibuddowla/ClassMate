begin;
alter table classmate.profiles
  add column mobile_number text,
  add column home_town text,
  add column blood_group text check(blood_group in('A+','A-','B+','B-','AB+','AB-','O+','O-','Unknown')),
  add column current_residence text,
  add column profile_completed_at timestamptz,
  add column profile_remind_after timestamptz;

create function classmate.save_profile_details(target_mobile text,target_town text,target_blood text,target_residence text default null)
returns classmate.profiles language plpgsql security definer set search_path='' as $$
declare phone text:=regexp_replace(trim(target_mobile),'[ ()-]','','g'); town text:=trim(target_town);
  blood text:=case when upper(trim(target_blood))='UNKNOWN' then 'Unknown' else upper(trim(target_blood)) end;
  residence text:=nullif(trim(target_residence),''); result classmate.profiles%rowtype;
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  if phone ~ '^01[0-9]{9}$' then phone:='+88'||phone;
  elsif phone ~ '^00[1-9][0-9]{7,14}$' then phone:='+'||substring(phone from 3); end if;
  if phone is null or phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'Enter a valid mobile number with country code' using errcode='22023'; end if;
  if town is null or length(town) not between 2 and 100 then raise exception 'Home town must contain 2 to 100 characters' using errcode='22023'; end if;
  if blood is null or blood not in('A+','A-','B+','B-','AB+','AB-','O+','O-','Unknown') then raise exception 'Choose a blood group' using errcode='22023'; end if;
  if length(residence)>150 then raise exception 'Current mess or flat is too long' using errcode='22023'; end if;
  update classmate.profiles set mobile_number=phone,home_town=town,blood_group=blood,current_residence=residence,
    profile_completed_at=now(),profile_remind_after=null,updated_at=now() where id=auth.uid() returning * into result;
  return result;
end $$;
create function classmate.defer_profile_completion() returns classmate.profiles
language plpgsql security definer set search_path='' as $$
declare result classmate.profiles%rowtype;
begin
  if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
  update classmate.profiles set profile_remind_after=now()+interval '24 hours',updated_at=now() where id=auth.uid() returning * into result;
  return result;
end $$;

-- Directory membership is independent of global administration. Teachers appear
-- only in batches where they have an active assignment and an active allowlist entry.
create function classmate.batch_directory_member(target_batch uuid,target_profile uuid) returns boolean
language sql stable security definer set search_path='' as $$
  select exists(select 1 from classmate.profiles p join classmate.batches b on b.id=target_batch and b.is_active
    where p.id=target_profile and p.verification_status='active' and (
      (p.role='student' and p.batch_id=b.id and p.department_id=b.department_id)
      or (p.role='teacher' and exists(select 1 from classmate.teacher_course_assignments a
        join classmate.semester_courses sc on sc.id=a.semester_course_id
        join classmate.semesters s on s.id=sc.semester_id
        join classmate.teacher_allowlist t on t.email=p.email and t.department_id=b.department_id and t.active
        where a.teacher_id=p.id and a.active and s.batch_id=b.id and s.status='active'))));
$$;
create function classmate.batch_friends(target_batch uuid,query_text text default '',result_offset integer default 0)
returns table(profile_id uuid,full_name text,student_id text,role text,is_cr boolean,avatar_url text)
language plpgsql stable security definer set search_path='' as $$
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not exists(select 1 from classmate.batches where id=target_batch and is_active) then
    raise exception 'This batch directory is unavailable' using errcode='42501'; end if;
  if result_offset<0 or result_offset is null or length(query_text)>100 then raise exception 'Invalid directory query' using errcode='22023'; end if;
  return query select p.id,p.full_name,p.student_id,p.role::text,p.is_cr,
    case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end
    from classmate.profiles p join auth.users u on u.id=p.id
    where classmate.batch_directory_member(target_batch,p.id)
      and position(lower(coalesce(trim(query_text),'')) in lower(p.full_name||' '||coalesce(p.student_id,'')))>0
    order by p.full_name,p.id limit 100 offset result_offset;
end $$;
create function classmate.batch_friend_details(target_batch uuid,target_profile uuid) returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare result jsonb;
begin
  if not classmate.is_active() or not classmate.can_read_batch(target_batch) or not classmate.batch_directory_member(target_batch,target_profile) then
    raise exception 'This profile is unavailable in your batch' using errcode='42501'; end if;
  select jsonb_build_object('profile_id',p.id,'full_name',p.full_name,'student_id',p.student_id,'role',p.role,
    'mobile_number',p.mobile_number,'home_town',p.home_town,'blood_group',p.blood_group,'current_residence',p.current_residence,
    'avatar_url',case when left(coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture',''),8)='https://'
      then coalesce(u.raw_user_meta_data->>'avatar_url',u.raw_user_meta_data->>'picture') end)
    into result from classmate.profiles p join auth.users u on u.id=p.id where p.id=target_profile;
  return result;
end $$;
revoke all on function classmate.batch_directory_member(uuid,uuid) from public,anon,authenticated;
revoke all on function classmate.save_profile_details(text,text,text,text),classmate.defer_profile_completion(),classmate.batch_friends(uuid,text,integer),classmate.batch_friend_details(uuid,uuid) from public,anon;
grant execute on function classmate.save_profile_details(text,text,text,text),classmate.defer_profile_completion(),classmate.batch_friends(uuid,text,integer),classmate.batch_friend_details(uuid,uuid) to authenticated;

alter table classmate.notices add column silent boolean not null default false;
create function classmate.normalize_silent_notice() returns trigger language plpgsql set search_path='' as $$
begin
  if new.title ~* '^/silent([[:space:]]|$)' or new.body ~* '^/silent([[:space:]]|$)' then
    new.silent:=true;
    new.title:=trim(regexp_replace(new.title,'^/silent([[:space:]]+|$)','','i'));
    new.body:=trim(regexp_replace(new.body,'^/silent([[:space:]]+|$)','','i'));
    if length(new.title)=0 then raise exception 'Add a notice title after /silent' using errcode='22023'; end if;
  end if;
  return new;
end $$;
create trigger normalize_silent_notice before insert or update of title,body on classmate.notices for each row execute function classmate.normalize_silent_notice();
create or replace function classmate.enqueue_notice() returns trigger language plpgsql set search_path='' as $$
begin
  if new.silent then return new; end if;
  insert into classmate.notification_outbox(kind,record_id,department_id,batch_id,semester_course_id,routing_label)
  values('notice',new.id,new.department_id,new.batch_id,new.semester_course_id,classmate.routing_label(new.batch_id,new.semester_course_id));
  return new;
end $$;
revoke all on function classmate.normalize_silent_notice() from public,anon,authenticated;
notify pgrst,'reload schema';
commit;
