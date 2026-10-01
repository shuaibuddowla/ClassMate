# Original prompt gap audit (1 October 2026)

Source: the owner's original 61-section backend, auth, role, department and semester prompt.
This audit distinguishes deployed backend behavior from the production beta Android UI.

## Implemented and exercised

- The additive `classmate` schema has departments, batches, eight semester rows per batch, courses, profiles, owner and teacher allowlists, assignments, routine, notices, class changes, file metadata, bus schedules, outbox and audit records.
- University Google Auth, server-side owner/teacher/student classification, CSE email parsing, manual pending onboarding, verification, CR expiry, semester publishing/cloning, and teacher-course permissions are implemented in migrations 006-010.
- RLS and RPC checks were exercised with transactional two-batch, pending student, CR and teacher fixtures. Production owner sign-in, one private R2 file round-trip and FCM receipt were tested on Android.
- The production beta now opens a running-batch selector for the owner; teacher selection derives from assigned active offerings. The four academic tabs plus a role-gated fifth Manage tab exist. The visible academic feed is scoped to the selected batch.
- The Supabase academic activity now uses the original timetable, notice, library, and profile layouts. On-device checks verified the seven balanced day pills, fixed timetable header, blue course icons, floating navigation that hides and returns on scroll, and an opaque pinned notice header. Timetable requests show the shimmer and discard stale day responses.

## Remaining work against the original prompt

1. **Original Android UI parity:** The Supabase activity now inflates the original four academic layouts, but some detail screens and data-dependent elements still differ from the old Firebase-backed app. In particular, only the files uploaded into the new Supabase/R2 catalog appear (one file in the inspected batch); the legacy archive has not been imported, existing notice attachments are not all linked to R2 metadata, and routine cards lack teacher names when no accessible teacher assignment exists. Complete those migrations before declaring full parity.
2. **Manage UX:** Departments, users, CRs, teachers, batches, semesters, courses, routine and bus schedule are grouped under Manage. Verification, CR assignment, semester publishing and teacher allowlisting now use record selectors. Other owner forms still expose UUID fields; replace them with record pickers, validation and edit/activate/deactivate flows. Add user counts, department/batch filters, batch roster and a profile detail page.
3. **Semester editing:** The backend supports draft creation, cloning, publishing and adding courses/routine slots. Android still needs a complete draft editor, routine update/delete, teacher assignment overview and per-batch status screen.
4. **Profile:** The beta reads the authoritative Supabase profile but displays only part of the requested fields. Add a profile view showing department, assigned batch, CR status and current semester from server records.
5. **Bus and notice features:** The beta shows bus departures and notices; the original rich bus and notice screens have not been fully ported. Friends, Chat and Polls are deliberately hidden in the Supabase beta at the owner's request until migrated.
6. **Real role journeys:** Two real student Google accounts from different batches and a real teacher account are unavailable. Their sign-in, onboarding and screen journeys remain unverified, although the database permission boundary has synthetic tests.
7. **Release packaging:** The delivered beta APK is debug signed. A release keystore and release build are still needed for public distribution.

## FCM design difference

The original prompt described server-derived FCM topics. The implementation sends to server-registered device tokens after RLS-based audience calculation, retaining a server-derived batch/course routing label in the outbox. This avoids relying on client topic subscriptions for confidentiality. The server-side isolation goal is met; the mechanism differs from the literal topic design.

The old Firebase-backed fragments and `public` data remain in the repository for migration reference. The current default Android target opens Supabase Auth and the restored academic activity; FCM is retained for push delivery.
