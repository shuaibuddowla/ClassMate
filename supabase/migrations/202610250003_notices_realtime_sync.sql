begin;

-- Enable full replica identity so UPDATE WAL events include all columns for realtime filters
alter table classmate.notices replica identity full;
alter table classmate.class_changes replica identity full;
alter table classmate.routine_slots replica identity full;
alter table classmate.academic_calendar_events replica identity full;
alter table classmate.bus_schedules replica identity full;

-- Add tables to supabase_realtime publication
do $$
declare
  t text;
begin
  for t in select unnest(array[
    'notices',
    'class_changes',
    'routine_slots',
    'academic_calendar_events',
    'bus_schedules'
  ])
  loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'classmate' and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table classmate.%I', t);
    end if;
  end loop;
end $$;

notify pgrst, 'reload schema';

commit;
