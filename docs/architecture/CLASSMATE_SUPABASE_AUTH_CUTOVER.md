# ClassMate Supabase Auth architecture and cutover

## Current state and conflict

The existing `public` V2 schema and Android client identify people with Firebase
UIDs. The V2 `profiles.id` is a generated UUID, and Firebase Auth tokens are sent
to Supabase Third-Party Auth. The app also still reads and writes several
Firestore collections, uses OneSignal for its current notice push flow, and has
Firebase-dependent login, splash, profile, and management screens. None of those
identifiers can safely be reinterpreted as `auth.users.id`. Replacing the
existing tables in place would break users and could lose data. The new
`classmate` schema is an additive parallel target, deployed in staging and
production and used by the staging and production beta Android builds.

## Final relational model

`auth.users.id` is the primary key of `classmate.profiles`. The server resolves
owner, then active teacher allowlist, then student. CR is an expiring flag on a
student profile. A department owns batches; a batch has eight semester rows;
semester courses join a reusable course to a semester. Teacher assignments
reference semester courses. Routine slots and class changes reference the semester course. Notices
and R2 metadata carry an explicit department and batch and an optional semester
course. Bus schedules are university wide for active users. Foreign keys and triggers reject mismatched scopes. `notification_outbox`
and `audit_log` are server only.

The seeded CSE department has prefix `ce`, offset `3`, and is active. For
`ce25045@mbstu.ac.bd`, onboarding derives `CE25045`, session `25`, and batch
`22` server side. Onboarding creates the batch and its eight empty semester
slots if necessary. A department without a parsing rule creates a pending
manual profile with no assigned batch. An owner approves or rejects it in one
database transaction. A pending profile can read its own profile and active
department choices, but not academic data.

## RLS and authority

All `classmate` tables have RLS. Academic tables are read only through policies
based on the current Supabase Auth user and live database relationships. A
student reads active content in their assigned batch. A teacher sees assigned
batch content and can post only to an assigned semester course. A CR can post
batch notices only while their assignment is current. The owner can see all
departments. Authority table writes occur only in security-definer RPCs that
check the owner allowlist; clients have no direct write grants for profiles,
roles, academic structure, notices, or file metadata. Device token writes are
scoped to the active authenticated profile. `app_owners` and the notification
outbox have no client read policy.

## Authentication and API contract

Configure Supabase Google Auth and the `classmate.before_user_created` Auth
Hook. The hook rejects a non-Google or non-`@mbstu.ac.bd` signup. The profile
RPC also checks `auth.users.email_confirmed_at` and a Google identity before it
creates a profile. The hook cannot verify email confirmation before the user is
created, so the RPC is the access gate. Disable other sign-in methods in the
Supabase dashboard. The Android client must use a Supabase session obtained by
exchanging a Google ID token, then use the `classmate` PostgREST schema.

| Operation | Request | Response | Errors and client behavior |
| --- | --- | --- | --- |
| `create_or_initialize_profile` | none | authoritative profile | Unconfirmed or wrong domain: end flow and show account error. Admin/teacher: proceed. Student with no department: show selector. |
| `complete_student_onboarding` | `selected_department`, optional manual `entered_student_id`, `entered_batch_number`, `entered_session` | profile | Wrong prefix: show mismatch. `pending`: show waiting screen, never normal tabs. `active`: enter app. |
| `approve_student_profile` | `target_profile`, corrected ID, real `target_batch`, corrected session | profile | Admin only. Reload roster and audit. |
| `reject_student_profile` | `target_profile`, `reason` | profile | Admin only. Student sees `rejection_reason`. |
| `assign_cr` / `revoke_cr` | profile, batch, optional expiry / profile | profile | Admin only. Reload authoritative profile. |
| `create_department` / `configure_department` | name/code / prefix/offset/active | department | Admin only. New department starts inactive. |
| `create_batch` | department, number, session | batch | Admin only. Trigger creates eight slots. |
| `create_course`, `add_semester_course`, `add_routine_slot` | matching IDs and fields | created row | Admin only; semester draft editing. |
| `save_bus_schedule` | optional ID, route, time, origin, destination, weekdays, notes, active | bus schedule | Admin only; active users can read. |
| `assign_teacher_to_course` | teacher profile, semester course | assignment | Admin only; allowlist and department checked. |
| `clone_semester` / `publish_semester` | source/target / target | UUID / semester | Admin only; target empty and draft; publish completes old active semester atomically. |
| `post_notice` | batch, optional semester course, title, body | notice | Owner, current CR, or assigned teacher only. Client never sends audience topic. |
| `post_class_change` | batch, assigned semester course, kind, date, details | class change | Assigned teacher or owner only; matching course and batch required. |
| `register_device_token` | FCM token | void | Active profile only. Refresh on token rotation and sign-in. |
| `signed-classmate-resource` Edge Function | `resource_id` | 60-second R2 GET URL | JWT and RLS required; 404 for inaccessible file. |
| `upload-classmate-resource` Edge Function | `action=start` with batch/course/title/type/MIME/size, then `action=finish` with resource ID | 120-second R2 PUT URL, then active status | JWT and live post authority required; R2 object length and MIME checked before activation. |

For reads, query `classmate.profiles` for the own profile; `departments` for
active onboarding options; `batches`, `semesters`, `semester_courses`, `courses`,
`routine_slots`, `notices`, `class_changes`, `file_metadata`, and `bus_schedules` for RLS filtered content. The
admin can query pending profiles, batch rosters, departments, batches, and
semester data under the owner policy. Never use locally calculated role,
department, student ID, batch, or CR state as authority.

## Notifications

Notice or class-change insertion, file activation, and semester publication enqueue a database
event. `dispatch-classmate-notifications` reads those events with a service key,
rechecks current active student membership, and sends FCM HTTP v1 to registered
device tokens. Labels use `dept_<code>_batch_<number>` and optional course code.
The label is diagnostic; the FCM request uses a token. FCM topics are publicly
subscribable by a modified client, so raw topic delivery cannot satisfy strict
cross-batch confidentiality ([Firebase topic subscription documentation](https://firebase.google.com/docs/cloud-messaging/manage-topic-subscriptions)). Run the dispatcher from a trusted scheduled POST
with `x-dispatch-secret`; do not expose that secret in Android.

## Deployment status (30 September 2026)

Both Supabase projects have migrations 006 through 010, the exposed `classmate`
API schema, the Before User Created Auth hook, and the three Edge Functions.
Both have private, separate R2 buckets with bucket-scoped server credentials.
Both have FCM HTTP v1 service account secrets and a private Cloudflare Worker
calling the notification dispatcher every minute. Google Auth is enabled and
the owner account has signed in through the Android Supabase Auth client.
The owner allowlist and active CSE email rule were seeded server side.

The staging physical Android device registered an FCM token. A disposable
batch 22 student received one real FCM notification; the user confirmed it
appeared. All staging test fixtures and temporary token changes were removed.
Staging also passed the R2 upload, activation, signed download, and byte
comparison, plus fixture-based RLS isolation checks.

Production has one real owner Supabase Auth profile and device token. A
disposable batch 22 FCM test returned one delivered notification. Its
temporary records were removed and the owner device token was restored. The
one-time additive import in `supabase/tests/import_public_v2_academics.sql`
completed. It mapped one CSE batch, eight courses, five active course
offerings, twelve current routine slots, and ten published notices into
`classmate`. One semester is active. Historical notice outbox events were
removed in the same transaction, preventing old notices from being pushed.
The source `public` tables are intact. Six source offerings included one
inactive offering; thirteen source routine slots included one deleted slot.
No source academic resources or notice attachments existed at import time.
The production owner Android session uploaded a 44-byte text file through the
Edge Function and private R2, activated its metadata, and opened the exact
contents through a signed download link. The temporary object, metadata,
outbox event, and phone test file were removed. Production's transactional
synthetic role test checked students in batches 22 and 23, pending student
isolation, current and expired CR scope, teacher course scope, and per-batch
outbox routing. Its fixtures rolled back completely.
The Android sign-out removed the registered production device token; sign-in
registered one token again. A second production FCM test returned one delivery,
and Android's notification manager recorded the "ClassMate update" notice
on the connected physical phone.

The Android build has three targets: `legacy` (default), `staging`, and
`production`. Staging and production use the new Supabase Auth activity and
server-enforced `classmate` API; legacy remains the existing Firebase app.
The Supabase activity supports university Google sign-in, encrypted refresh
token storage through Android Keystore, profile creation and onboarding,
role-gated academic reads, owner management RPCs, notices, class changes,
R2 upload/download, Android notification permission, and FCM token
registration. Posting and uploading choose an RLS-visible batch and active
course by name. Owner setup forms still require some UUIDs. This is a
functional beta screen, not yet feature or visual parity with the original
Android screens. The 1 October revision adds a running-batch chooser for the
owner, an assignment-scoped chooser for teachers, a fifth Manage tab, and
batch-scoped feeds. See `CLASSMATE_ORIGINAL_PROMPT_GAP_AUDIT.md` for the
remaining prompt gaps and the reason the old Firebase fragments cannot simply
be opened after Supabase Auth sign-in.

## APKs and build

Use Android Studio JDK 21 and the ignored `local.properties` values for
Supabase URLs and publishable keys:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio2\jbr'
.\gradlew.bat :app:assembleDebug -PclassmateTarget=staging --offline
.\gradlew.bat :app:assembleDebug -PclassmateTarget=production --offline
```

The staging artifact is `deliverables/ClassMate-staging-test.apk`. The
production beta artifact is `deliverables/ClassMate-production-beta.apk`.
These are debug-signed test APKs using the same application ID as the
existing app. Each build includes a Supabase publishable key only; R2,
FCM, and dispatcher secrets remain server side. Install one target at a
time, because they share an application ID. Store signing credentials
securely before producing a distributable release APK.

## Validation and remaining work

The synthetic SQL tests establish RLS behavior for two CSE batches,
pending students, CR expiry, teacher assignments, and semester uniqueness.
Real Google accounts from two separate batches and one teacher are not
currently available. Their sign-in and cross-batch UI journeys remain
unverified; transactional production RLS fixtures cover the permission
boundary without creating lasting accounts. The production Worker's scheduled
cron event returned `Ok`. Production on-device FCM receipt is confirmed.
Complete feature parity of the original Android UI has not yet been verified.

Do not retire the existing Firebase-backed public V2 client or delete its
data until the remaining real-account security tests and UI migration are
complete. The production beta does not change the default legacy build.
