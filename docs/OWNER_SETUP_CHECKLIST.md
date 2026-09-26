# ClassMate V2 owner setup checklist

This file contains the identity-sensitive and external-account steps that must
be completed by the project owner. Do them against a disposable development
project first. Never paste passwords, OAuth client secrets, service-role keys,
service-account JSON, or keystore contents into source control or an AI chat.

## 1. Confirm the permanent Android identity

The current application ID is `com.shuaib.classmate`. Decide whether this is the
final ID before configuring more integrations. Changing it later affects Google
OAuth, Firebase registration, installed-app upgrades, and release signing.

If it remains final, use exactly `com.shuaib.classmate` everywhere below.

## 2. Create and protect the release signing key

In Android Studio, choose **Build > Generate Signed Bundle / APK > APK > Create
new**. Create one release keystore and keep the same signing identity for every
sideloaded update.

Store the following outside the repository:

- the `.jks`/`.keystore` file;
- alias;
- keystore password;
- key password.

Keep at least two encrypted backups in separate locations. The repository now
ignores keystore files and `keystore.properties`, but that is only a last line
of defense.

Run the signing report using the Android Studio terminal and copy both debug and
release SHA-1/SHA-256 fingerprints into your private records:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio2\jbr'
.\gradlew.bat signingReport
```

If the release variant says `Config: null`, release signing has not yet been
wired into Gradle. Do not commit its passwords when you add it.

## 3. Create a disposable Supabase development project

1. Create a new Supabase project in a nearby region.
2. Save the database password in your password manager.
3. From **Project Settings > API**, copy only:
   - Project URL;
   - Publishable key, or the legacy anon key if publishable keys are unavailable.
4. Never copy the `service_role` key into Android or GitHub.
5. Put the client values in the root, untracked `local.properties`:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLIC_CLIENT_KEY
```

Adding both values activates the V2 data client. Firebase remains the only app
session; its ID token is sent to Supabase for RLS-protected data access.

## 4. Connect Firebase Authentication to Supabase

Do **not** enable the Google provider under Supabase **Authentication > Sign In
/ Providers**. Do not copy a Google client secret into Supabase.

1. In Firebase Console, open **Project settings > General** and copy the exact
   **Project ID** (not the project number or Android app ID).
2. In Supabase, open **Authentication > Third-Party Auth**.
3. Add a **Firebase** integration and enter that Firebase Project ID.
4. Save it. Hosted Supabase will then verify Firebase ID tokens issued by that
   registered project.
5. Keep Google enabled in Firebase **Authentication > Sign-in method**.
6. Ensure the Android Firebase app uses package `com.shuaib.classmate` and has
   both debug and release SHA-1/SHA-256 fingerprints.

Supabase requires the custom Firebase claim `role: "authenticated"`. The
repository contains `onFirebaseUserCreated` for future users and a one-time
backfill script for existing users. Deploy and backfill them in Step 5 before
testing V2 data access.

## 5. Deploy the Firebase role-claim function

From the repository root, log in to the Firebase CLI, verify that `.firebaserc`
points to the disposable/development Firebase project, then deploy functions:

```powershell
firebase login
firebase use
cd functions
npm ci
npm run build
cd ..
firebase deploy --only functions:onFirebaseUserCreated
```

The trigger handles only users created after deployment. Existing Firebase
users need the one-time backfill. The safest route is Google Cloud Shell for
the same project because it supplies Application Default Credentials:

```bash
git clone YOUR_PRIVATE_REPOSITORY_URL
cd ClassMate/functions
npm ci
gcloud config set project YOUR_FIREBASE_PROJECT_ID
npm run auth:backfill
```

If you run it locally instead, set `GOOGLE_APPLICATION_CREDENTIALS` to a
service-account JSON file stored outside this repository, run the command, then
remove/revoke that key as appropriate. Never send or commit the JSON file.
Changing custom claims does not update an already-issued token; sign out and
back in, or force a token refresh, before testing.

## 6. Apply the database migrations

Install the Supabase CLI, then from the repository root:

```powershell
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push --dry-run
supabase db push
```

Read the dry-run output before applying. Never use `supabase db reset --linked`
against production; that command destroys remote data.

The migrations currently create:

- academic hierarchy, profiles, scoped roles, timetable, notices, resources,
  engagement data, device tokens, and audit records;
- RLS policies for students, CRs, teachers, and admins;
- admin functions for semester publishing/cloning, staff allowlisting, role
  grants/revocation, and student assignment overrides;
- a private 50 MB `academic-resources` Storage bucket with RLS.

## 7. Seed real structure and bootstrap the first admin

1. Copy `supabase/bootstrap.example.sql` to
   `supabase/bootstrap.local.sql`.
2. Replace every `CHANGE_ME` value using real institutional data.
3. Review the owner email carefully.
4. Run the local copy in the Supabase SQL editor.
5. Only after it succeeds, use **Continue with Google** in the app with that
   exact owner email.

The pre-login allowlist entry is essential: the secured
`bootstrap_firebase_profile` RPC creates the owner profile and global admin
grant when that Google account first signs in.

Before testing ordinary students, create every needed department prefix and
batch code. For example, `ce25045@mbstu.ac.bd` needs an active department whose
email prefix is `ce` and a non-archived batch whose cohort code is `25`.
Otherwise the account correctly becomes `pending_setup`.

Do not invent or send me institutional data. You must supply the department
list, prefixes, batches, sections, semester dates, course catalog, teacher
emails, CR accounts, routine, and bus schedule.

## 8. Configure staff and roles

- Add a teacher/admin email to `staff_allowlist` before that staff member's
  first sign-in.
- Admin allowlist entries receive their admin grant during first sign-in.
- Teachers become active users but receive actual teaching permissions only
  after their course offerings exist and an admin grants the teacher role for
  each offering.
- CR grants must identify a batch and should normally have an expiry date.
- Use `grant_scoped_role`, `revoke_role`, and
  `override_student_assignment` through the admin client/RPC once its screens
  are connected; avoid direct table edits after bootstrap.

## 9. Verify Firebase and FCM

The existing Firebase project remains necessary for FCM during and after this
migration.

1. Register package `com.shuaib.classmate` in Firebase.
2. Add debug and release SHA fingerprints.
3. Download the current `google-services.json` into `app/` if Firebase tells you
   the existing file is outdated.
4. Test token creation and a notification on physical hardware.
5. Keep FCM server/service-account credentials only in a trusted server or
   Supabase Edge Function secret store.

## 10. Security test matrix before enabling V2 as primary

Create separate test accounts and verify both allowed and rejected operations:

| Account | Must be allowed | Must be rejected |
| --- | --- | --- |
| Student | Read own scope, like/pin/remind | Create or edit shared content |
| CR | Post to granted batch/section | Post to another batch or after expiry |
| Teacher | Post/upload for assigned offering | Modify an unassigned course |
| Scoped admin | Manage granted department/batch | Manage outside that scope |
| Global admin | Manage all academic structure | Bypass authentication |
| Blocked/pending | Read own profile/status | Read protected academic data |

Also test a fake client request directly against the REST API. Hiding buttons is
not an authorization test. Confirm private Storage files cannot be downloaded
without an authorized JWT.

## 11. Physical-device acceptance tests

Test at least:

- Google sign-in using debug and release-signed APKs;
- a valid student, unknown batch, staff, CR, teacher, admin, and blocked account;
- app restart/session restoration;
- FCM notification routing;
- upload/download/open PDF and image resources;
- offline timetable, notices, bus schedule, and downloaded files;
- reminder delivery after reboot;
- APK update, SHA-256 verification, and upgrade over the installed version;
- one older or lower-end Android device.

## 12. Production readiness

Before production, create a separate production Supabase project, repeat the
reviewed migrations and real seed, configure backups, publish a privacy policy,
move OAuth consent out of Testing when appropriate, and run the complete role
matrix again. Keep development and production keys separate.

Useful official references:

- Supabase CLI workflow: https://supabase.com/docs/guides/local-development/cli-workflows
- Supabase Firebase third-party auth: https://supabase.com/docs/guides/auth/third-party/firebase-auth
- Supabase Storage access control: https://supabase.com/docs/guides/storage/security/access-control
- Firebase Android setup: https://firebase.google.com/docs/android/setup
- Android app signing: https://developer.android.com/studio/publish/app-signing
