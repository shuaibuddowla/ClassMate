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
