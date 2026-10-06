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

## Signup hook correction
Migration 202610060003_teacher_signup_hook updates the Before User Created hook, which runs before profile initialization. Approved Gmail exceptions are now enforced at both stages. Staging rollback tests cover approved Gmail, institutional student, unapproved Gmail, revoked Gmail and non-Google provider. Production read-only check confirms the registered approved Gmail is accepted. Actual Google login must be retried from Teacher sign-in; an old failed callback cannot be reused.

## Teacher feed and Android consent follow-up
Teacher notices are queried by author before 10-item pagination and filtered in cached rendering; empty feeds invite a first notice. Empty teaching days use a teacher-specific message, preserving calendar holiday closures. Returning Android users are offered Enable/Later notification permission without replaying profile setup; successful permission and foreground/network reconnect retry device-token registration. No phone was connected over ADB for a real-device alert test; production diagnostics recorded 89 displayed client receipts during the preceding seven days across registered clients.
Background update checks automatically download and verify new APKs when automatic updates are enabled, respecting Wi-Fi preferences. The verified APK stays cached and a ready notification is retained. App entry offers Update/Later; only the explicit Update path may open the installer. PackageInstaller requires user action. A cached APK can be installed after consent without waiting for Wi-Fi again. Wi-Fi preferences, signature/hash verification and existing release payloads remain intact. Android changes require signed version 1.1.26 (27); no APK was installed or published in this follow-up.
