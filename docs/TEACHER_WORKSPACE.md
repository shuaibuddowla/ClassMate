# Teacher workspace (6 October 2026)

## Access and setup
- Manage > Teachers: edit the existing teacher record, enter an approved university email or Gmail, and assign that record to the relevant courses in an active semester.
- Teacher sign-in uses Google OAuth without the student hosted-domain hint. The server requires the exact active teacher allowlist email and a verified Google identity.
- Unapproved Gmail is denied. Existing institutional student registration is unchanged. Existing student/admin accounts cannot be promoted by registering their email as a teacher.
- Replacing/removing an approved email revokes the old teacher account and course access.
- Do not register a second record for the same teacher. Md. Shazzat Hossain is the existing OOP/OOP Lab teacher; the production directory contains one matching record.

## Familiar interface
Teachers choose an assigned active batch after sign-in. Timetable shows their assigned courses; Students lists that batch's students. Teaching tools under Profile exposes assigned courses, teaching schedule, course notices/cancellations, and resources. Owners retain global management tools. Bus/calendar information and existing backend/file permissions remain in place.

## Deployment and verification
Migration 202610060002_teacher_gmail_workspace applied in staging then production. Web deployed to https://classmatebd.vercel.app and staging alias. Android source version 1.1.26 (27); assembleDebug and unit tests pass. No signed APK published or installed for this task.

Staging rollback SQL tests verify approved Gmail initialization, unapproved Gmail rejection, assigned batch/course scope, cross-batch denial, student compatibility, owner-only teacher management, and old-email revocation. Playwright teacher workspace test passes on staging and production using mocked authenticated data; it is not a real Google OAuth login test. Web typecheck, 25 unit tests and production build pass. Android 37 unit tests pass. Production read-only verification found no synthetic test profiles.

## Admin testing
Edit the existing teacher in Manage > Teachers and assign your test Gmail yourself. Sign out and choose Teacher sign-in with that Google account. Choose an assigned batch and check your courses, timetable, notices and resources. A real approved Gmail OAuth round trip still requires this user test.
