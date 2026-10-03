# Student bus schedules

Add Bus now requires a schedule category (Office open or Closed / holidays) and
two departure times: Campus → City and City → Campus. The form has no vehicle
details, trip number, route-name, free-text notes or weekday checklist. Editing
also offers the existing enable/disable control.

The timetable's Bus Schedule view shows paired departure times on one card.
The selected timetable date determines the list: Thursday and Friday use Closed /
holidays, while Saturday–Wednesday use Office open. There is no extra day-type
toggle on the viewing page. Add Bus inherits the selected date's category.
`ClassMateBusDays.kindFor(date, calendarHoliday)` is the integration point for a
future academic calendar: a holiday on any weekday uses the closed schedule.
The owner-supplied 2026 calendar is now configured in Supabase. Office closures
override the weekly rule; class-only closures do not. See `ACADEMIC_CALENDAR.md`.
The editor still lets administrators manage either recurring schedule
category. These paired times are independent departures, not travel duration;
the city time is not required to be exactly 30 minutes after the campus time.

Only the owner and an active, unexpired CR may save these schedules, enforced by
the server. Repeated active time pairs within a category are rejected. Original
legacy records and the old save RPC remain available; records with no city time
display their original one-way route without inventing a return time. A legacy
record becomes a paired student schedule only when saved through the new editor.

Migrations: `202610030002_student_bus_departures.sql` and
`202610030003_bus_university_weekends.sql`.
Staging verification: `supabase/tests/classmate_student_bus_departures.sql`
(rollback-only permission, time, duplicate, editing, disabling and compatibility
checks). After the user's explicit request, all 16 supplied departure pairs were
imported into production Supabase (10 office-open and 6 closed/holiday schedules).
Their times are stored only in the database, not in Android source or resources.

Version: 1.1.19 (20). Sign with the existing key0 identity before publishing.
