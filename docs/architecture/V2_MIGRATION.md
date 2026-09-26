# ClassMate V2 migration

This document tracks the controlled migration from the current Firebase-backed
application to the architecture in the master project specification.

## Non-negotiable decisions

- Keep one Android application and the existing application identity.
- Use Firebase Authentication as the single identity provider and pass its ID
  tokens to Supabase Third-Party Auth for PostgreSQL RLS authorization.
- Keep Firebase Cloud Messaging for push delivery.
- Derive student department, batch code, and roll number on the server from a
  verified `@mbstu.ac.bd` Google account.
- Authorize staff from server-side records. Never trust a role or academic scope
  supplied by the Android client.
- Model university, department, batch, section, semester, and course scope from
  the beginning.
- Keep notice attachments and Library resources connected through one resource
  record.
- Preserve the existing Room cache, WorkManager jobs, Android widgets, PDF
  handling, and update flow where practical.

## Migration strategy

The migration uses a strangler approach: new backend-neutral repository
interfaces will be placed in front of each feature, and each Firebase
data implementation will be replaced one vertical slice at a time. Firebase
Auth and FCM remain; Firestore and Firebase Storage are removed only after every
required slice has reached parity.

1. Database foundation and RLS.
2. Google sign-in, profile bootstrap, and role-aware app context.
3. Departments, batches, sections, semesters, courses, and staff assignments.
4. Timetable, bus schedules, and structured class changes.
5. Notices, audience targeting, likes, pins, reminders, and FCM fan-out.
6. Library resources, notice attachments, offline files, and versioning.
7. Administration screens, audit views, and migration of existing data.
8. Remove obsolete Firebase data paths after production verification.

## First database migration

`supabase/migrations/202609240001_v2_foundation.sql` establishes:

- the complete academic hierarchy;
- server-owned profiles and student identity parsing;
- scoped, expiring role grants;
- semester course offerings and teacher assignments;
- timetable, class-change, notice, resource, and bus-schedule records;
- notice engagement, local-reminder metadata, device tokens, and audit records;
- reusable authorization functions and initial RLS policies.

The migration intentionally does not create a Storage bucket, an FCM sender, or
semester publish/clone functions. Those designs are still open in the master
specification and should be reviewed before implementation.

## Manual prerequisites

Before applying migrations, the project owner must create the Supabase project,
retain the service-role secret only in trusted server environments, register
the Firebase Third-Party Auth integration, deploy the Firebase role-claim
function, and decide how the first administrator will be
bootstrapped. Database changes should first be applied to a disposable Supabase
project and tested with separate student, CR, teacher, and admin accounts.

## Definition of done for Phase 1

- A student-pattern Google account is classified only by server-side code.
- An unknown batch produces a `pending_setup` profile rather than an invented
  batch assignment.
- Non-student-pattern accounts are blocked unless they appear in the staff
  allowlist.
- Expired role grants no longer authorize writes.
- Students cannot modify administrative or shared academic data.
- CR writes are restricted to their granted batch/section.
- Teacher writes are restricted to assigned course offerings.
- Admin writes are restricted by their granted scope.
- RLS behavior has been manually tested with all four user roles.

## Implementation status

Completed in source control:

- V2 PostgreSQL schema, RLS, server-side Google profile bootstrap, scoped roles,
  integrity triggers, audit logging, semester publish/clone functions, and
  private academic-resource Storage policies.
- Android Supabase client using Firebase ID tokens, secured Firebase-profile
  bootstrap, profile/role loading,
  backend-neutral academic catalog, and schedule repositories.
- Owner bootstrap template and external-account setup checklist.
- Embedded PostgreSQL smoke coverage for migration syntax, allowlisted admin
  bootstrap, student email parsing, and active-semester publication.

Awaiting owner-operated environment setup:

- create the development Supabase project and connect Firebase Third-Party Auth;
- deploy the Firebase custom-claim function and backfill existing users;
- apply migrations and seed real institutional structure;
- test RLS and private Storage using separate role accounts and physical devices.

Next coding slices:

1. Notices, targets, likes, pins, reminders, and FCM event delivery.
2. Library/resource metadata, Supabase Storage upload/download, bookmarks, and
   version replacement.
3. Replace timetable/bus UI reads after live data parity is verified.
4. Admin management screens backed by the scoped RPCs.
5. Harden the GitHub release updater with required version metadata and SHA-256.
6. Retire obsolete Firestore/Storage data paths only after parity; Firebase Auth
   and FCM remain part of the final architecture.
