# Supabase backend

The files in this directory define the legacy Firebase-backed `public` V2
schema and the additive Supabase Auth `classmate` schema. Both migration chains
have been applied to ClassMate Staging and production. New changes should be
validated on staging before production.

Typical owner-run workflow after installing the Supabase CLI:

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

Never place the Supabase service-role key in the Android application, this
repository, `local.properties`, or any client-visible environment variable.

## Android configuration

The legacy V2 client uses these entries in the untracked
root `local.properties` file:

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_PUBLISHABLE_KEY=your-publishable-or-anon-key
```

Only a publishable/anon client key belongs in the Android app. In the legacy
V2 path, Firebase Auth is
the single identity provider. The client supplies its Firebase ID token to
Supabase Third-Party Auth; there is no second Supabase user session. When both
values are present, Google sign-in bootstraps/refreshes the linked V2 profile;
failure is logged and the legacy Firebase data flow continues during migration.

For the existing V2 path, add the Firebase project under **Authentication > Third-Party
Auth**. That path does not use Supabase's Google provider. Every Firebase user token
that accesses Supabase must contain the custom claim `role: "authenticated"`;
the Android client obtains it through the Vercel role bridge before its first
Supabase request. See the owner checklist for deployment, verification, and the
optional existing-user backfill command.

The complete owner-operated setup and verification procedure is documented in
`docs/OWNER_SETUP_CHECKLIST.md`. Start from `bootstrap.example.sql` when seeding
a disposable project; never put real owner/institutional data into that tracked
template.

## OneSignal push delivery

Batch push requests go directly from the Android client to the deployed
`send-notification` Edge Function. The function verifies the Firebase ID token,
checks batch permission through Supabase, constrains delivery to the batch tag,
and sends through OneSignal. Other secure APIs can continue using Cloudflare;
the notification send path no longer goes through its Worker.

Before pushes can be delivered, configure the OneSignal credentials as Supabase
Edge Function secrets (never put the REST API key in the app or repository):

```powershell
supabase secrets set ONESIGNAL_APP_ID=<onesignal-app-id> ONESIGNAL_REST_API_KEY=<onesignal-rest-api-key> --project-ref <project-ref>
```

The app ID is public configuration; the REST API key is a server-only secret.
To deploy later changes:

```powershell
supabase functions deploy send-notification --project-ref <project-ref> --use-api --no-verify-jwt
```

The function disables gateway JWT verification intentionally because it
validates Firebase ID tokens itself against Google's signing keys and issuer.

## Supabase Auth beta

The additive `classmate` schema in migrations `202609290006` through
`202609290010` targets Google sign-in through Supabase Auth, with
`classmate.profiles.id` referencing `auth.users.id`. It is separate from the
Firebase-backed V2 `public` schema above. Its deployment, manual settings,
Android cutover contract, and required staging checks are in
`docs/architecture/CLASSMATE_SUPABASE_AUTH_CUTOVER.md`. The schema and three
Edge Functions are deployed to staging and production. The production owner
has signed in, the old academic rows have been imported additively, and a
debug-signed production beta APK is available in `deliverables`. Select the
new Android client with `-PclassmateTarget=staging` or
`-PclassmateTarget=production`; the default build remains the legacy V2 app.
Real cross-role Google identity tests and full original-screen parity still
need work. See the architecture document for deployed settings, tests, and
remaining release tasks.
