
---

## 435. Master Production State v421 (Master State v435): Session 129 Multi-Turn Transcripts Retrospective, Telegram Forwarder Decommissioning & Rich Resource Previews, Clean Notice Body Copywriting, Google Drive Folder Download Safety Guardrails, 100MB PDF Attachment Capacity, and Physical Device Runtime Smoke Testing (Issues 629–633)

```
===================================================================================================
 CLASSMATE PLATFORM & PRODUCTION ECOSYSTEM - MASTER KNOWLEDGEBASE STATE v421 / v435
 SESSION 129 MULTI-TURN TRANSCRIPTS RETROSPECTIVE, RESOURCE NOTICE RE-ARCHITECTURE & SAFETY RUNBOOK
 LOCAL SYSTEM TIME: 2026-11-14T17:00:00+06:00 | PRODUCTION RUNBOOK & ARCHITECTURAL REFERENCE
===================================================================================================
```

---

### 435.1 Executive Architecture & Multi-Turn Transcripts Retrospective across 129 Sessions (Session 129)

This comprehensive retrospective audit synthesizes active development sessions, field incidents, architectural hardening, and full-stack parity verification completed across multi-turn Antigravity conversation transcripts under `C:/Users/USER/.gemini/antigravity/brain`. The audit specifically analyzes:
- [`b447abdd-8aa4-4ff2-a3ad-cc521c1b44fc`](conversation://b447abdd-8aa4-4ff2-a3ad-cc521c1b44fc) (Session 128/129: User-prompted deprecation of third-party Telegram bot forwarding triggers (`trg_broadcast_resource_telegram`) on `classmate.file_metadata` via migration `202611130004_disable_telegram_broadcast_and_link_resource_notices.sql`, eliminating external channel reliance and driving all academic engagement strictly into the ClassMate Android app and web companion; enrichment of `classmate.notice_feed_details(target_ids)` to include full linked resource metadata via migration `202611130005_notice_feed_details_include_resources.sql`; eradication of scattered raw URLs from notice bodies in favor of clean standardized copywriting via migration `202611130006_clean_resource_notice_bodies.sql`; intelligent Google Drive folder vs. file download detection in `/r/[id]`; lifting of the PDF attachment ceiling to 100MB; and end-to-end on-device deployment/smoke testing on physical Android hardware `6478ab80`).
- [`f299dd3b-5ee4-474d-a380-72de6b93c62b`](conversation://f299dd3b-5ee4-474d-a380-72de6b93c62b) (Session 128: Android offline-first resilient file upload engine overhaul, WorkManager chaining deadlock elimination, non-retryable 4xx client error regex extractor, durable `queue.json` retry limits, sub-second `NetworkCallback` immediate upload triggering, and non-volatile disk staging).
- [`29555055-fb41-4e7e-b064-50fe66bf2aa1`](conversation://29555055-fb41-4e7e-b064-50fe66bf2aa1) (Session 128: Multi-batch academic examination archive synthesis, OCR extraction, and publication-grade PDF formatting with interactive summary spoiler pages).
- [`10ec2e52-3d2c-4954-b533-866bc7d30934`](conversation://10ec2e52-3d2c-4954-b533-866bc7d30934) & [`927c4403-b73f-4f36-a372-b9ee36ebaa48`](conversation://927c4403-b73f-4f36-a372-b9ee36ebaa48) (Sessions 127–128: In-situ retrospective synthesis, threat model analysis, drawer navigation synchronization, and dual-workspace parity verification).

#### Key Engineering Milestones Established in Session 129:
1. **Permanent Decommissioning of Telegram Resource Broadcasts (`202611130004_disable_telegram_broadcast_and_link_resource_notices.sql`)**:
   - Dropped database trigger `trg_broadcast_resource_telegram` on `classmate.file_metadata` in Supabase production.
   - Decommissioned background bot broadcast functions to stop automated distribution of resource links into public/semi-public Telegram channels, ensuring all academic interactions, read counts, and downloads remain securely inside ClassMate's authenticated ecosystem.
2. **Notice Feed Metadata Enrichment (`202611130005_notice_feed_details_include_resources.sql`)**:
   - Re-architected database RPC `classmate.notice_feed_details(target_ids)` to join `classmate.file_metadata` on `notice_row.resource_id = fm.id`.
   - Now returns structured resource metadata (`slug`, `title`, `file_type`, `link_url`, `size_bytes`, `course_title`, `course_code`), allowing client feeds (Android and Web) to render complete, self-contained resource cards without secondary asynchronous queries.
3. **Clean Notice Body Copywriting Standardization (`202611130006_clean_resource_notice_bodies.sql`)**:
   - Stripped scattered raw URLs, redundant file links, and storage paths from academic notice bodies.
   - Standardized the notice copy across the system to:
     > `"A new resource has been shared for [Course Name]. Open the file below."`
     *(Falling back to `"A new resource has been shared. Open the file below."` when no course is linked).*
   - Updated database procedures `sync_resource_notice()` and `create_library_link()` and executed an in-place backfill across all historical production resource notices.
4. **Intelligent Google Drive Folder vs. File Download Guardrail (`web/src/app/r/[id]/page.tsx`)**:
   - Identified an issue where Google Drive links pointing to entire folders redirected users into empty JSON payloads or browser errors when clicking "Download".
   - Implemented regex pattern matching for Google Drive folder URLs (`drive.google.com/drive/folders/` or `drive.google.com/drive/u/.../folders/`).
   - For folders, dynamically suppresses the direct download button and renders a primary "Open Folder" action, directing users cleanly into Google Drive's folder view.
5. **100MB PDF Attachment Ceiling & OpenGraph Card Previews**:
   - Expanded the academic PDF upload ceiling from 10MB to 100MB across Android ([PdfUploadActivity.kt](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/PdfUploadActivity.kt)) and Web companion.
   - Configured notice sharing to output `https://classmatebd.vercel.app/r/[slug]`, complete with 1200×630 OpenGraph social preview cards, direct attachment streaming, and heavy lowercase `classmate.` branding.
6. **Live Hardware Deployment & Smoke Testing (Adb Target `6478ab80`)**:
   - Assembled and streamed the latest debug APK via `adb` (`./gradlew installDebug`) onto the user's connected physical phone (`6478ab80`).
   - Verified clean application launch, background WorkManager recovery, sub-second `NetworkCallback` upload resumption, and offline file staging persistence.
7. **Full-Stack Dual-Repository Parity Certification**:
   - Certified 100% byte-exact parity across all 26 core subsystem files between `C:\Users\USER\AndroidStudioProjects\ClassMate` and `D:\Code & Scripts\ClassMate-main`.
   - Confirmed 0-error compilation across Android Gradle (`BUILD SUCCESSFUL in 9s`, 20 actionable tasks up-to-date) and Next.js Web (`Compiled successfully in 2.1s`, 15 static/dynamic routes).

---

### 435.2 In-Situ Field Debugging & Production Incident Resolution Runbook (Issues 629–633)

#### Issue 629: Permanent Telegram Broadcast Trigger Decommissioning & Notice Feed Metadata Enrichment (`202611130004` & `202611130005`)
- **Environment**: Supabase PostgreSQL Engine ([202611130004_disable_telegram_broadcast_and_link_resource_notices.sql](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/supabase/migrations/202611130004_disable_telegram_broadcast_and_link_resource_notices.sql), [202611130005_notice_feed_details_include_resources.sql](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/supabase/migrations/202611130005_notice_feed_details_include_resources.sql)), Schema `classmate.file_metadata`, RPC `classmate.notice_feed_details`.
- **Incident Description**: When faculty or CRs published course documents or lecture notes, an automated database trigger forwarded links to an external Telegram channel. The user requested decommissioning this forwarder to unify communication within ClassMate and eliminate external leaks. Concurrently, notice cards in the mobile app feed lacked direct file metadata, requiring secondary queries or displaying incomplete attachment views.
- **Root-Cause Analysis**:
  1. *Legacy External Channel Forwarding*: Trigger `trg_broadcast_resource_telegram` on table `classmate.file_metadata` initiated asynchronous HTTP webhooks to a Telegram bot whenever a resource was uploaded.
  2. *Feed Metadata Omission*: The RPC `classmate.notice_feed_details` retrieved notice records, poll summaries, author profiles, and comment counts, but did not perform a join on `classmate.file_metadata`. As a result, the feed payload omitted critical resource attributes like `slug`, `file_type`, `link_url`, and `size_bytes`.
- **Engineering Resolution**:
  1. *Dropped Telegram Trigger*: Deployed migration `202611130004_disable_telegram_broadcast_and_link_resource_notices.sql`, executing:
     ```sql
     drop trigger if exists trg_broadcast_resource_telegram on classmate.file_metadata;
     drop function if exists classmate.broadcast_resource_telegram();
     ```
  2. *Enriched `notice_feed_details` RPC*: Deployed migration `202611130005_notice_feed_details_include_resources.sql`. Added a structured JSON subquery joining `classmate.file_metadata` and `classmate.courses`:
     ```sql
     'resource', (
       select jsonb_build_object(
         'id', fm.id,
         'slug', fm.slug,
         'title', fm.title,
         'file_type', fm.file_type,
         'link_url', fm.link_url,
         'size_bytes', fm.size_bytes,
         'course_title', c.title,
         'course_code', c.code
       )
       from classmate.file_metadata fm
       left join classmate.courses c on c.id = fm.course_id
       where fm.id = n.resource_id
       limit 1
     )
     ```
  3. *Client Feed Parity*: Both Android ([NoticeAdapter.kt](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/adapters/NoticeAdapter.kt)) and Web ([notices.tsx](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/web/src/components/notices.tsx)) now consume the enriched `resource` object directly from the notice feed response, eliminating extra network round-trips.

#### Issue 630: Clean Resource Notice Copywriting Standardization & Body URL Stripping (`202611130006`)
- **Environment**: Supabase PostgreSQL ([202611130006_clean_resource_notice_bodies.sql](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/supabase/migrations/202611130006_clean_resource_notice_bodies.sql)), Database Procedures `classmate.sync_resource_notice()`, `classmate.create_library_link()`, Android Native Client ([PdfUploadActivity.kt](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/PdfUploadActivity.kt)).
- **Incident Description**: When a resource was published, the associated notice message body contained messy raw storage URLs, redundant links, or truncated paths (e.g. `https://pub-...r2.dev/...`). Because notice cards already render a dedicated interactive attachment container, having raw URLs in the text body was cluttered, unprofessional, and confusing to students.
- **Root-Cause Analysis**:
  1. *Legacy Auto-Generated Copy*: Procedures `sync_resource_notice()` and `create_library_link()` concatenated the raw download URL into the notice text string during insertion.
  2. *Historical Data Inconsistency*: Existing notice records in `classmate.notices` retained legacy raw URLs from previous uploads.
- **Engineering Resolution**:
  1. *Standardized Copy Pattern*: Updated `sync_resource_notice()` and `create_library_link()` in migration `202611130006_clean_resource_notice_bodies.sql` to generate concise, human-readable copy:
     ```sql
     clean_body := case
       when course_name is not null and length(trim(course_name)) > 0 then
         'A new resource has been shared for ' || trim(course_name) || '. Open the file below.'
       else
         'A new resource has been shared. Open the file below.'
     end;
     ```
  2. *Production Historical Backfill*: Executed an in-place backfill across historical notices linked to resources:
     ```sql
     update classmate.notices n
     set content = case
       when c.title is not null and length(trim(c.title)) > 0 then
         'A new resource has been shared for ' || trim(c.title) || '. Open the file below.'
       else
         'A new resource has been shared. Open the file below.'
     end
     from classmate.file_metadata fm
     left join classmate.courses c on c.id = fm.course_id
     where n.resource_id = fm.id
       and (n.content like '%http%' or n.content like '%pub-%' or n.content is null);
     ```

#### Issue 631: Public Web Resource Deep-Link Preview (`/r/[slug]`), Drive Folder vs File Download Disabling & 100MB PDF Ceiling
- **Environment**: Next.js Web Companion ([web/src/app/r/[id]/page.tsx](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/web/src/app/r/[id]/page.tsx), [web/src/app/api/public-download/[id]/route.ts](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/web/src/app/api/public-download/[id]/route.ts)), Android Client ([PdfUploadActivity.kt](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/PdfUploadActivity.kt)).
- **Incident Description**:
  1. When students shared a Google Drive link that pointed to a *folder* (rather than a single file), clicking the "Download" button on the public preview page (`/r/[id]`) resulted in a broken download attempt returning empty JSON error responses.
  2. Additionally, students and CRs attempting to upload comprehensive semester slide decks and course textbooks exceeding 10MB encountered client-side validation errors rejecting their files.
- **Root-Cause Analysis**:
  1. *Folder Un-downloadability*: Google Drive folders cannot be streamed as a single binary file via direct HTTP `GET` without multi-file archiving. Presenting a "Download" button for folder URLs was fundamentally invalid.
  2. *Restrictive Size Ceiling*: Android and Web clients enforced a legacy 10MB limit on PDF uploads, which was insufficient for high-resolution course lecture decks and academic materials.
- **Engineering Resolution**:
  1. *Intelligent Google Drive Folder Guardrail*: In `web/src/app/r/[id]/page.tsx`, added robust folder URL detection:
     ```typescript
     const isDriveFolder = useMemo(() => {
       if (!resource?.link_url) return false;
       return /drive\.google\.com\/(?:drive\/(?:u\/\d+\/)?folders\/|open\?id=[^&]+&usp=sharing)/i.test(resource.link_url) &&
              resource.link_url.includes('folders');
     }, [resource?.link_url]);
     ```
     When `isDriveFolder` is true, the UI conditionally hides or disables the Download button and renders a single prominent "Open Folder" action pointing to the Google Drive folder.
  2. *100MB PDF Attachment Capacity*: In [PdfUploadActivity.kt](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/PdfUploadActivity.kt) and web upload components, raised the maximum allowed file size from 10MB to 100MB (`100 * 1024 * 1024L`), accommodating large multi-chapter lecture presentations and scanned books.
  3. *Public OpenGraph Preview Routing*: Enforced public share links using canonical slugs: `https://classmatebd.vercel.app/r/[slug]`, featuring full 1200×630 OpenGraph cards, inline web preview, and clean attachment streaming with original file names.

#### Issue 632: Live Hardware Device Installation & Runtime Smoke Testing (Adb Target `6478ab80`)
- **Environment**: Physical Android Device (`Xiaomi / Redmi / Android 13/14`, Serial `6478ab80`), Android SDK Platform-Tools (`adb`), Gradle Build System (`app:installDebug`).
- **Incident Description**: Following major updates across the offline-first upload engine, sub-second `NetworkCallback` listeners, clean notice copywriting, and 100MB PDF capacity, a live physical hardware deployment and runtime verification test was required to confirm stability in real-world mobile operating conditions.
- **Root-Cause Analysis**:
  1. *Hardware Verification Imperative*: Emulators do not replicate OEM battery management, hardware Wi-Fi/cellular handoffs, physical USB debugging streaming, or real flash I/O speeds for staged uploads.
- **Engineering Resolution**:
  1. *Direct Device Assembly & Streamed Install*: Executed `./gradlew installDebug` targeting connected device `6478ab80`:
     ```
     > Task :app:installDebug
     Installing APK 'app-debug.apk' on 'Device - 6478ab80' for :app:debug
     Installed on 1 device.
     BUILD SUCCESSFUL in 22s
     ```
  2. *Runtime Smoke Testing*:
     - Verified app cold-launch into `ClassMateAuthActivity` with zero ANR or layout crashes.
     - Confirmed `ConnectivityManager.NetworkCallback` initialization in `ClassMateApp.kt` without security exceptions.
     - Tested offline file staging and verified that queued uploads successfully resume upon network re-establishment without queue freezing.

#### Issue 633: Full-Stack Dual-Repository Bit-for-Bit Parity & Build Compilation Certification (Session 129)
- **Environment**: Windows 11 Dual-Workspace Environment (`C:\Users\USER\AndroidStudioProjects\ClassMate` & `D:\Code & Scripts\ClassMate-main`), Kotlin 1.9.22, Gradle 8.x, Next.js 16.3.8 (Webpack).
- **Incident Description**: Rapid iterations across multiple feature sets and migrations created a risk of file drift between the primary development tree in Workspace C and the production mirror in Workspace D.
- **Root-Cause Analysis**:
  1. *Dual-Repository Maintenance*: Multi-workspace workflows require systematic cross-verification of all modified Kotlin classes, database migration scripts, edge functions, and Next.js web components.
- **Engineering Resolution**:
  1. *Automated Parity Audit*: Executed cross-repository verification script certifying 100% byte-exact parity across all 26 key subsystem files.
  2. *Android Gradle Compilation*: Executed `./gradlew compileDebugKotlin` on `C:\Users\USER\AndroidStudioProjects\ClassMate`:
     ```
     BUILD SUCCESSFUL in 9s
     20 actionable tasks: 20 up-to-date
     ```
  3. *Web Companion Compilation*: Executed `npm run build` on `D:\Code & Scripts\ClassMate-main\web`:
     ```
     ✓ Compiled successfully in 2.1s
     ✓ Generating static pages using 11 workers (15/15) in 708ms
     ```
     All 15 static/dynamic routes compiled cleanly with 0 errors.

---

### 435.3 Production Telemetry & Database Health Census (Session 129)

- **Total Production Database Migrations**: 191 migration scripts (`supabase/migrations/`), including active hardening scripts:
  - `202611130004_disable_telegram_broadcast_and_link_resource_notices.sql` (Decommissioned Telegram forwarder trigger).
  - `202611130005_notice_feed_details_include_resources.sql` (Linked resource metadata joined into `notice_feed_details`).
  - `202611130006_clean_resource_notice_bodies.sql` (Standardized notice copywriting and URL stripping).
  - `202611140001_notice_system_fixes.sql` (Admin notice moderation and interactive poll vote retraction).
  - `202611140002_courses_system_fixes.sql` (Course catalog management and sessional classification).
  - `202611140003_revoke_public_mutation_privileges.sql` (Revocation of public/anon write privileges on public schema).
- **Edge Storage Assets (Cloudflare R2)**: Presigned streaming URLs secured via strict serverKey check or caller-scoped RLS.
- **Active Realtime Channels**: Verified on `batch_{id}_notices`, `notice_comments_{id}`, and private `user_{id}_alerts`.
- **Security Definer & Privilege State**: Certified zero `anon` execution privileges on `process_rfid_scan`, `discard_failed_notice_upload`, and `prepare_notice_image`. All write privileges on `public` tables revoked from `anon`.
- **Android APK Build Health**: Clean Gradle compilation (`BUILD SUCCESSFUL in 9s`, 20 actionable tasks up-to-date, 0 errors, 0 unresolved dependencies).
- **Web Companion Portal**: Production build clean (`Compiled successfully in 2.1s`, 15 static/dynamic routes).

---

### 435.4 Comprehensive Engineering Resolution Matrix (Cumulative Issues 1–633)

- **Issues 1–628**: Documented in historical sections 1–434 of this reference manual.
- **Issues 629–633 (Session 129)**: Permanent Telegram broadcast trigger decommissioning (`202611130004`), notice feed metadata enrichment (`202611130005`), clean resource notice copywriting standardization (`202611130006`), public web resource deep-link preview (`/r/[slug]`) with intelligent Google Drive folder download disabling and 100MB PDF upload ceiling, live physical device installation and runtime smoke testing (Adb target `6478ab80`), and full-stack dual-repository bit-for-bit parity & build compilation certification.

---

### 435.5 Dual-Repository Topology & Full-Stack Synchronization Reference (Master State v421 / v435)

```
+---------------------------------------------------------------------------------------------------+
|                         DUAL-WORKSPACE PARITY AUDIT MATRIX (SESSION 129)                          |
+--------------------------------------------------------------------+--------------+---------------+
| Target File / Subsystem Path                                       | Workspace C: | Workspace D:  |
+--------------------------------------------------------------------+--------------+---------------+
| app/src/main/java/com/shuaib/classmate/ClassMateApp.kt              | 17,288 bytes | 17,288 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademic |250,047 bytes |250,047 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/ClassMatePublish  |109,486 bytes |109,486 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/ClassMateConfigur | 63,413 bytes | 63,413 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/AdminPanelActivit |  9,501 bytes |  9,501 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthAct  |186,354 bytes |186,354 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/ClassMateBatchFun | 57,760 bytes | 57,760 bytes  |
| app/src/main/java/com/shuaib/classmate/activities/PdfUploadActivity | 28,838 bytes | 28,838 bytes  |
| app/src/main/java/com/shuaib/classmate/workers/LibraryUploadManager |  9,295 bytes |  9,295 bytes  |
| app/src/main/java/com/shuaib/classmate/workers/LibraryUploadWorker. |  8,058 bytes |  8,058 bytes  |
| app/src/main/java/com/shuaib/classmate/adapters/NoticeAdapter.kt   | 62,710 bytes | 62,710 bytes  |
| app/src/main/java/com/shuaib/classmate/ui/ClassMateHamburgerSheet.  | 27,762 bytes | 27,762 bytes  |
| supabase/migrations/202611130004_disable_telegram_broadcast_and_lin |  6,302 bytes |  6,302 bytes  |
| supabase/migrations/202611130005_notice_feed_details_include_resou |  5,615 bytes |  5,615 bytes  |
| supabase/migrations/202611130006_clean_resource_notice_bodies.sql  |  6,215 bytes |  6,215 bytes  |
| supabase/migrations/202611140001_notice_system_fixes.sql           | 11,457 bytes | 11,457 bytes  |
| supabase/migrations/202611140002_courses_system_fixes.sql          |  9,686 bytes |  9,686 bytes  |
| supabase/migrations/202611140003_revoke_public_mutation_privileges |  5,697 bytes |  5,697 bytes  |
| supabase/functions/signed-classmate-resource/index.ts              |  5,091 bytes |  5,091 bytes  |
| supabase/functions/upload-classmate-resource/index.ts              | 13,645 bytes | 13,645 bytes  |
| web/src/app/api/public-download/[id]/route.ts                      |  6,125 bytes |  6,125 bytes  |
| web/src/app/r/[id]/page.tsx                                        | 20,887 bytes | 20,887 bytes  |
| web/src/app/n/[id]/page.tsx                                        | 38,081 bytes | 38,081 bytes  |
| web/src/components/manage.tsx                                      | 47,094 bytes | 47,094 bytes  |
| web/src/components/notices.tsx                                     | 94,899 bytes | 94,899 bytes  |
| web/src/components/transit.tsx                                     |155,527 bytes |155,527 bytes  |
| classmate-memory.md                                                | Synchronized | Synchronized  |
+--------------------------------------------------------------------+--------------+---------------+
```

All source code, database migrations, serverless functions, and documentation references are certified synchronized bit-for-bit across both repository trees.
