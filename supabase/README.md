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

Only a publishable/anon client key belongs in the Android app. The current
Firebase screens remain active while V2 authentication and data slices are
verified against a disposable Supabase project. When both values are present,
Google sign-in also creates or refreshes the Supabase session in shadow mode;
failure is logged and the Firebase flow continues.
