begin;

create table if not exists classmate.teacher_greetings (
    slot text primary key,
    start_hour integer not null check (start_hour between 0 and 23),
    end_hour integer not null check (end_hour between 0 and 23),
    greeting text not null,
    eyebrow text not null default 'TEACHING SCHEDULE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

insert into classmate.teacher_greetings (slot, start_hour, end_hour, greeting, eyebrow) values
('morning', 5, 11, 'Good morning, Sir', 'TEACHING SCHEDULE'),
('noon', 12, 14, 'Good noon, Sir', 'TEACHING SCHEDULE'),
('afternoon', 15, 17, 'Good afternoon, Sir', 'TEACHING SCHEDULE'),
('evening', 18, 22, 'Good evening, Sir', 'TEACHING SCHEDULE'),
('night', 23, 4, 'Good evening, Sir', 'TEACHING SCHEDULE')
on conflict (slot) do update set
    start_hour = excluded.start_hour,
    end_hour = excluded.end_hour,
    greeting = excluded.greeting,
    eyebrow = excluded.eyebrow,
    updated_at = now();

alter table classmate.teacher_greetings enable row level security;

drop policy if exists read_teacher_greetings on classmate.teacher_greetings;
create policy read_teacher_greetings on classmate.teacher_greetings for select to authenticated using (true);

drop policy if exists manage_teacher_greetings on classmate.teacher_greetings;
create policy manage_teacher_greetings on classmate.teacher_greetings for all to authenticated using (classmate.is_owner()) with check (classmate.is_owner());

create or replace function classmate.get_teacher_greeting(client_hour integer default null)
returns jsonb language plpgsql stable security definer set search_path='' as $$
declare
    current_h integer;
    result record;
begin
    if not classmate.is_active() then
        raise exception 'Active account required' using errcode='42501';
    end if;

    if client_hour is not null and client_hour between 0 and 23 then
        current_h := client_hour;
    else
        current_h := extract(hour from (now() at time zone 'Asia/Dhaka'))::integer;
    end if;

    select slot, greeting, eyebrow into result
    from classmate.teacher_greetings
    where (
        (start_hour <= end_hour and current_h >= start_hour and current_h <= end_hour)
        or
        (start_hour > end_hour and (current_h >= start_hour or current_h <= end_hour))
    )
    order by case
        when slot = 'noon' then 1
        when slot = 'morning' then 2
        when slot = 'afternoon' then 3
        when slot = 'evening' then 4
        else 5
    end
    limit 1;

    if result.greeting is null then
        return jsonb_build_object(
            'greeting', 'Good day, Sir',
            'eyebrow', 'TEACHING SCHEDULE',
            'slot', 'default'
        );
    end if;

    return jsonb_build_object(
        'greeting', result.greeting,
        'eyebrow', result.eyebrow,
        'slot', result.slot
    );
end $$;

revoke all on function classmate.get_teacher_greeting(integer) from public, anon;
grant execute on function classmate.get_teacher_greeting(integer) to authenticated;

notify pgrst, 'reload schema';
commit;
