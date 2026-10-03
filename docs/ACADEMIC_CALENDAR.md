# Academic calendar

Timetable now offers Class Routine, Bus Schedule and Calendar to every signed-in
user. Calendar hides the seven-day strip and shows a selectable monthly grid,
month navigation, today's date, event lists, closure categories, moon-dependent
dates, publication notes and sync status. It follows light/dark themes and caches
last-synced years privately per account. The floating navigation still reacts to
scrolling. Only the global owner sees event management; RPCs enforce ownership.

## 2026 source and import

The app owner supplied the MBSTU 2026 printed calendar plus readable close-ups.
All 25 listed holidays and two additional observances were transcribed. Separate
class/office ranges for Ramadan and Eid-ul-Adha produce 29 stored rows covering
27 named events. The four class-only rows are Ramadan, Eid-ul-Adha, November 17
and December 14. University Day (October 16) and Bhashani's birthday (November 21)
are observances, not additional office closures. Marked dates are provisional
because they depend on moon sighting. The discretionary seven days have no
announced dates and are recorded in notes, never invented as holidays.

Data lives in `classmate.academic_calendar_events` and
`classmate.academic_calendar_publications`, not Android source/resources.
Source transcription is versioned in `supabase/seeds/mbstu_calendar_2026*.json`.
The original printed calendar is the source; later official amendments need an
owner edit. Re-running the import deliberately restores the source-keyed rows.

Import after applying `202610030004_academic_calendar.sql`:

```powershell
python -X utf8 scripts/import-classmate-calendar.py --target staging
python -X utf8 scripts/import-classmate-calendar.py --target staging --apply
python -X utf8 scripts/import-classmate-calendar.py --target production --apply
```

Without `--apply` the script validates data only. Imports are transactional and
keyed, preserving unrelated events and avoiding duplicate source records.

## Schedule integration

For the selected campus date, a university/office closure selects the six-row
closed bus schedule. A class-only holiday does not change office bus service.
An owner-defined exceptional working day uses the ten-row office-open schedule;
a simultaneous explicit office closure wins. Otherwise Thursday/Friday are
closed and Saturday–Wednesday are open. Existing departure pairs are unchanged.
Class Routine hides recurring periods on weekly holidays and class/university
closures, showing the holiday name and date instead. Day pills highlight closed
dates. Observances do not hide periods; explicit working days override weekly
closures. Saved timetable records remain intact and reappear on normal dates.

On cold bus load, calendar data is synced before selecting the schedule when
online. Cached calendar data permits immediate offline resolution; without a
previous sync, only weekly holidays are known. Stale caches refresh in the
background, and the visible bus list updates if the resolved day type changes.

## Verification

Rollback-only staging checks: `supabase/tests/classmate_academic_calendar.sql`
cover owner create/edit/delete, invalid ranges/scopes, signed-in reads,
non-owner write denial, direct delete denial and anonymous access denial.
Android unit tests cover all weekdays, calendar holidays and exceptional working
days. Source-boundary checks verify distinct class/office dates. Staging and
production each contain all 29 source rows plus publication notes.

Version 1.1.19 (20); use the existing key0 signing and GitHub publishing workflow.
No ADB installation or phone UI control.
