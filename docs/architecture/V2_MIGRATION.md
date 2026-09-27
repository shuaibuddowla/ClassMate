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
- Interpret the email's two-digit student session as the admission session and
  derive the academic batch code by subtracting three (`25` becomes `22`).
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
the Firebase Third-Party Auth integration, verify the Vercel role-claim bridge,
and decide how the first administrator will be
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
  bootstrap, profile/role loading, and a compatibility bridge that makes the
  server-verified V2 role and student academic scope authoritative in the
  existing Android UI during feature-by-feature cutover,
  backend-neutral academic catalog, and schedule repositories.
- RLS-filtered Supabase notice feed read model connected to the existing Room
  cache alongside legacy Firestore notices for a non-destructive cutover.
- Authenticated Supabase RPC actions for notice likes, personal pins, and
  authorized global pins, with engagement counts returned by the feed.
- Scoped publishing for plain-text batch notices, with target resolution and
  authorization enforced inside PostgreSQL.
- V2 notice likes, personal/global pins, and per-user reminders stored under
  Supabase RLS; local reminder delivery continues through WorkManager.
- Batch notice push authorization through the deployed Cloudflare worker, with
  V2 Supabase role checks and legacy Firestore authorization retained.
- V2 academic course and routine reads from Supabase. Library file uploads
  continue through the MBSTU CSE Archive API so its R2 file and website
  metadata stay in one workflow. The ClassMate Worker now checks archive
  access against Supabase roles rather than Firestore roles.
- Stable JSON response decoding for Android notice publishing.
- Vercel role-claim bridge using short-lived Vercel OIDC credentials and Google
  Workload Identity Federation, with no service-account private key.
- Owner bootstrap template and external-account setup checklist.
- Embedded PostgreSQL smoke coverage for migration syntax, allowlisted admin
  bootstrap, student email parsing, and active-semester publication.

Awaiting owner-operated environment setup:

- create the development Supabase project and connect Firebase Third-Party Auth;
- verify a real Android Google sign-in reaches the deployed Vercel claim bridge;
- apply migrations and seed real institutional structure;
- test RLS and private Storage using separate role accounts and physical devices.

Next coding slices:

1. Notice-resource attachments, Library bookmarks, and resource version replacement.
2. Schedule source cutover is implemented: V2 Supabase routine and bus reads
   feed the existing student UI and offline Room cache; admin routine and bus
   edits use Supabase tables protected by RLS; structured class changes can be
   posted and are reflected in the timetable; teacher display uses a scoped
   read function. Firestore remains a compatibility fallback when V2 is not
   configured.
3. `202609280008_v2_schedule_teacher_read.sql` and
   `202609280009_v2_archive_access.sql` are applied to the linked development
   project. Add real course, routine and bus data through the app, then verify
   student, teacher and admin behavior with separate accounts.
4. Harden the GitHub release updater with required version metadata and SHA-256.
5. Retire obsolete Firestore/Storage data paths only after parity; Firebase Auth
   and FCM remain part of the final architecture.

## Current V2 boundary

The app can create a Supabase course offering from the timetable or Library
course picker, then create a Supabase routine slot for that offering. The
Library lists the Supabase courses and sends file bytes and archive metadata to
MBSTU CSE Archive through the Cloudflare Worker. New Library uploads therefore
appear on the Archive website once its upload completion endpoint succeeds.

V2 is still incomplete outside those paths: Library favorites and the separate
Question Bank screen, academic calendar exceptions, user profile screens,
chat, friends, polls and other older screens still access Firestore. Do not
describe the app as Firestore-free until those screens are ported and verified.
