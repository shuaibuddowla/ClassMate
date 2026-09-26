# Supabase backend

The files in this directory are the ClassMate V2 database source of truth.

Do not apply these migrations to production first. Create a disposable project,
apply the migrations there, seed real academic structure through an owner-run
process, and test RLS with student, CR, teacher, and admin accounts.

Typical owner-run workflow after installing the Supabase CLI:

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

Never place the Supabase service-role key in the Android application, this
repository, `local.properties`, or any client-visible environment variable.

## Android configuration

The V2 client remains disabled until these entries are added to the untracked
root `local.properties` file:

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_PUBLISHABLE_KEY=your-publishable-or-anon-key
```

Only a publishable/anon client key belongs in the Android app. Firebase Auth is
the single identity provider. The client supplies its Firebase ID token to
Supabase Third-Party Auth; there is no second Supabase user session. When both
values are present, Google sign-in bootstraps/refreshes the linked V2 profile;
failure is logged and the legacy Firebase data flow continues during migration.

In Supabase, add the Firebase project under **Authentication > Third-Party
Auth**. Do not configure Supabase's Google provider. Every Firebase user token
that accesses Supabase must contain the custom claim `role: "authenticated"`;
see the owner checklist for the deploy and existing-user backfill commands.

The complete owner-operated setup and verification procedure is documented in
`docs/OWNER_SETUP_CHECKLIST.md`. Start from `bootstrap.example.sql` when seeding
a disposable project; never put real owner/institutional data into that tracked
template.
