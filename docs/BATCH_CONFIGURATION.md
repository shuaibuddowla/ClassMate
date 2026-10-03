# Batch configuration and Manage

Manage now opens from Profile. Bottom navigation contains Timetable, Notices,
Library, Friends and Profile; Manage is not a swipe destination.

## Courses

Open Profile → Manage → Courses to add courses, choose a department catalog
course, edit the current batch's course details or delete a batch course.
Course code and name are required. Teacher name and credit are optional.
Names ending with `Lab` (case insensitive, with whitespace trimmed) become lab
courses; other new/edited names become theory courses. Existing course types
are preserved until the course is edited.

The timetable editor's Add Period control sits at the lower-right with a short
schedule and follows the final row with a long schedule. It never overlays a
period. When the batch has no courses, Add Period offers configuration. Library
also offers this to the admin or CR. Missing active semesters require the admin
to publish one under Manage → Batches & semesters.

Active CRs may add, edit and delete only their active batch's courses. Changes
use semester-course overrides, leaving the department catalog and other batches
unchanged. The timetable, Library and notice/upload pickers read the same
`batch_course_catalog` RPC. Existing teacher add/remove permissions are retained;
editing an existing course's details is restricted to the admin or batch CR.

Batch deletion preserves the shared catalog and other batches. Linked library
files must first be deleted through Library; existing deletion safeguards remain
in force. The confirmation explains removal of periods, notices and assignments.
Only the global admin sees shared catalog edit/delete actions. Global deletion
continues to use the existing impact preview and durable R2 cleanup queue.

## Teacher records

A configured teacher name creates a directory record, not an Auth account.
The saved name appears in timetable rows immediately. Existing records can be
chosen explicitly; equal names are not automatically merged. CRs can reuse
records already configured in their batch; the owner can select departmental
records across batches.

Profile → Manage → Teachers lets the owner add teachers, edit names, attach a
university email, assign a current-batch course and disable existing login access.
Saving a nonempty email enables the existing allowlist and links assigned courses
to a real teacher profile, either now or on subsequent registration. Names alone
do not grant access. Signed-in teachers with existing assignments are migrated
into directory records without changing their Auth accounts.

## People & approvals

This owner-only page shows real avatars and searchable names, IDs, batches and
account states, with 50 accounts fetched per page. CR toggles appear only for
eligible active students and require confirmation. Existing CR assignment,
revocation and expiry rules are enforced by the server. Manual pending profiles
can be approved or rejected; rejected manual profiles can be reconsidered.
Students and CRs cannot call the owner roster or teacher-management RPCs.
Friends remains batch-scoped.

## Verification

- `supabase/tests/classmate_batch_configuration.sql`: rollback-only staging test
  for CR edits, cross-batch rejection, student rejection, catalog preservation,
  teacher draft display, registration linking, automatic Lab detection, routine
  types, owner listing and batch deletion. No synthetic fixtures remain.
- Android debug/release assembly and existing unit tests pass.
- Production migration `202610030001` applied after staging validation; owner
  read-only RPC checks passed and existing profile/read-receipt counts stayed
  unchanged.
- No phone screenshots, remote phone control or ADB installation performed.
- The release still requires the existing `key0` signing workflow before GitHub
  publishing. Do not publish an unsigned or debug APK as an automatic update.

Visual checks on a phone remain for the user: dark/light themes, large font
settings, short/long schedules and return navigation from configuration.
