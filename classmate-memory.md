# ClassMate Notice Subsystem — Complete Architecture & Memory Reference

This memory file documents every detail of the Notice system across the Android application, Web client, and Supabase backend. It serves as an instant knowledge base for any future notice-related tasks.

---

## 1. High-Level Overview & Core Concepts

- **Purpose**: The Notice page/feed is the central academic broadcasting and engagement hub in ClassMate. It delivers announcements, class cancellations, resources/file attachments, exam notices, and deadlines to students and faculty.
- **Dual Architecture**:
  1. **Modern Supabase Academic Screens** (`ClassMateAcademicScreensSupabase.kt`): The active, production architecture powering ClassMate 1.1.23+ with smooth ListAdapter paging, real avatars, threaded comments, read receipts, and AI compose.
  2. **Legacy / Complementary Components** (`NoticeFragment.kt`, `NoticeAdapter.kt`, `NoticeRepository.kt`, Room DB `NoticeDao.kt`, Firestore): Kept for offline caching, hybrid fallbacks, and backward compatibility.
- **Web App Parity** (`web/src/components/notices.tsx`, `notice-comments.tsx`): Full functional parity with Android — responsive mobile feed and persistent desktop right-hand rail, real avatars, reaction toggles, keyset paging, translation, and reader dialogs.

---

## 2. Notice Types, Context Art & Presentation

Every notice card is visually distinct based on its classification:
- **General Notice**:
  - Background: `bg_notice_premium_general` (light gray/subtle surface)
  - Icon: Megaphone art (`ic_notice_megaphone_art`), tinted `cm_notice_premium_general` (`#4F46E5` / purple-blue)
  - Accent line: `bg_notice_premium_accent` / `cm_notice_premium_general`
- **Class Cancellation**:
  - Detected by: `class_change_id != null` or title containing cancellation keywords (`Class cancelled`, `cancellation`, etc.)
  - Background: `bg_notice_premium_cancel` (soft red/coral tint)
  - Icon: Calendar with cross art (`ic_notice_calendar_cancel_art`), tinted `cm_notice_premium_cancel` (`#DC2626` / red)
  - Dynamic Effect: Automatically links to `class_changes` in Supabase; affected periods in the Timetable turn red with `CANCELLED` badge instead of `CLASS`/`LAB`.
- **Resource / Protected File**:
  - Detected by: `resource_id != null` or title prefixed with `Resource: `
  - Background: `bg_notice_premium_resource` (soft emerald/teal tint)
  - Icon: Document/book illustration (`ic_notice_resource_art`), tinted `cm_notice_premium_resource` (`#059669` / green)
  - Attachment Card: Shows `tvFileName`, `tvFileSize` ("Tap to open protected file"), `tvFileExtension` ("FILE"), and opens via secure signed R2 URL (`openFile`).
- **No Extraneous Badges**:
  - No artificial status pills (like "Important", "Urgent", or "Exam" pills) are rendered inside modern cards; titles themselves can contain emphasis.

---

## 3. Card UI Layout & Performance Architecture (Android)

### Performance & Scrolling (`ClassMateNoticeRows.kt`)
- Implemented as an immutable **`ListAdapter<ClassMateNoticeRow, ClassMateNoticeRows.Holder>`** using `DiffUtil.ItemCallback`.
- **Row Signatures**: Each card has a unique composite signature string (`signature`) containing item data, read counts, engagement state, reader preview fingerprint, translation status, search query, and user role.
- **Granular Diffing**: When likes, read counts, or search queries change, only the affected row invalidates. The entire adapter is never reset, eliminating scroll jumps, flickering, and dropped frames.
- **View Retention**: ViewHolders and Glide image requests are preserved during paging updates.
- **State Restoration**: `stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY`.

### Text Rendering & Truncation (`ClassMateNoticeText.kt`)
- **Search Highlighting**: Matches are highlighted using `BackgroundColorSpan(cm_search_highlight)` (bright yellow) and `ForegroundColorSpan(cm_search_highlight_text)`.
- **URL Linkification**: `Linkify.addLinks(..., Linkify.WEB_URLS)` makes web URLs clickable (`LinkMovementMethod.getInstance()`), tinted `cm_primary`.
- **512-Character Layout Bound**: Cards clamp preview string length to `minOf(body.length, 512)` before calculating `StaticLayout`, preventing multi-thousand character rendering slowdowns.
- **Unicode Safety**: High-surrogate boundaries are respected (`Character.isHighSurrogate(body[end - 1])`) to prevent emoji truncation crashes.
- **3-Line Truncation & "… See more"**:
  - Binary search calculates the exact substring cutoff fitting within 3 lines along with `"… See more"`.
  - Clicking "See more" or clicking the card body opens the full notice dialog modal.
  - Partial URL spans across the cutoff boundary are safely removed.

### Notice Summary Row (`item_notice_summary.xml`)
- Rendered at the top of the feed (position 0 when active).
- Displays the most recent author's avatar (`ivSummaryAvatar`), name (`tvSummaryName`), total update count, and latest post date (`tvSummaryMeta`).

### Modern Notice Card Header & Body (`item_notice_modern.xml`)
- `noticeAccent`: 3dp colored vertical indicator matching the notice type.
- `tvTitle`: Max 3 lines bold, 18sp.
- `ivNoticeIllustration`: Megaphone, calendar cancellation, or resource art.
- `btnOptions`: 3-dot management button (`ic_more_vert`). Only enabled/opaque (alpha 1f) for owner, CR, or the notice's author. Opens action dialog with "Edit title and message" and "Delete notice".
- `tvPreview`: Truncated 3-line body.
- `tvMeta`: Date formatted with calendar clock outline icon (`ic_notice_date_small`).
- `btnTranslate`: Small language icon (`ic_translate_small`) + "Translate" / "Original".

### Card Footer Actions (`item_notice_modern.xml`)
1. **Notice Reminder (`btnReminder`, `ivReminderIcon`)**:
   - Bell outline (`ic_notice_reminder_outline`, muted) or active bell (`ic_notice_reminder_active`, tinted `cm_primary`).
   - Tapping opens reminder options: "In 1 minute", "In one hour", "Tomorrow at 9 AM", "Clear reminder".
   - Scheduled via `ClassMateNoticeReminderScheduler` (uses `AlarmManager.setExactAndAllowWhileIdle` and fallback `ClassMateNoticeReminderWorker`).
2. **Seen / Read Receipts (`btnReadReceipts`, `tvReadCount`, `seenAvatars`)**:
   - Overlapping reader avatars: CircleImageViews showing up to 4 real reader avatars (22x22 dp, marginStart offset = index * 14 dp, 1dp border).
   - Label: `X seen` (compact formatted: `1.2k`, `3.5m`).
   - Tapping opens the **Seen by** Dialog: Displays list of students who read the notice, with names, avatars, read timestamps, and **50-reader keyset pagination** ("Load more" via `notice_readers_page`).
3. **Idle Scroll Read Tracking (`markVisibleNoticesRead`)**:
   - Monitored on `RecyclerView.SCROLL_STATE_IDLE`.
   - Checks visible items where at least 50% of the card height is visible inside the RecyclerView bounds.
   - Debounces notice IDs into a local `noticeReadSent` set to prevent duplicate RPC calls.
   - Calls Supabase RPC `mark_notices_read(target_ids)`. Optimistically prepends user's avatar and increments seen count.
4. **Like Button (`btnLike`, `ivLikeIcon`, `tvLikeCount`)**:
   - Heart outline (`ic_heart_outline`) or filled (`ic_heart_filled`, tinted red `cm_notice_like`).
   - Optimistic toggle with debounced RPC update to `notice_reactions`.
5. **Comments Button (`btnComment`, `tvCommentCount`)**:
   - Chat bubble icon (`ic_comment_outline`) with comment count.
   - Tapping opens the threaded social comments modal / bottom sheet.

---

## 4. Social Comments System

- **Backend Table**: `classmate.notice_comments` (with `id`, `notice_id`, `author_id`, `parent_id`, `body`, `client_request`, `created_at`, `updated_at`, `deleted_at`).
- **Likes on Comments**: `classmate.comment_likes` (table linking `comment_id` and `profile_id`).
- **Features**:
  - **Threaded Replies**: Root comments (`parent_id = null`) and nested replies (`parent_id = root.id`).
  - **Keyset Pagination**: RPC `comment_page(target_notice, target_parent, before_time, before_id)` fetches 50 items per page sorted by `created_at DESC, id DESC`.
  - **Idempotency**: Unique constraint on `(author_id, client_request)` using client-generated UUID prevents duplicate posts on network retry.
  - **Soft Deletion**: `delete_notice_comment(target_id)` sets `deleted_at = now()` and soft-deletes child replies. Allowed only for comment author or app owner.
  - **In-place Editing**: `save_notice_comment` updates existing comment text (enforces max 2,000 characters).

---

## 5. Web Notice Page Architecture (`web/src/components/notices.tsx`)

- **Dual-Mode Rendering**:
  - Mobile: Full dedicated screen accessible via bottom navigation.
  - Desktop: Persistent, resizable right-hand sidebar rail (`NoticeRail`) alongside the schedule workspace.
- **Infinite Query (`useInfiniteQuery`)**:
  - Key: `[ctx.user, ctx.batch, "notices"]`.
  - 10 notices per page using keyset pagination: `published_at < cursor.published_at OR (published_at = cursor.published_at AND id < cursor.id)`.
  - Calls RPC `notice_feed_details` for each page to retrieve bulk engagement, reads, author profiles, and 4-avatar previews.
  - Auto-refreshes every 30 seconds when tab is active.
- **Intersection Observer**:
  - Automatically loads the next 10 notices when scrolling near the bottom `paging` sentinel.
- **Offline Resilience**:
  - Syncs pages to `localStorage` via `saveAcademic` / `readAcademic`. If offline, loads cached pages seamlessly.
- **Instant Search**:
  - Search input filters currently loaded notices with instant highlighted marks (`<mark>`).
- **Bengali Translation**:
  - Calls Edge Function `classmate-ai` with `mode: "translate"`.
  - Translates title and body to Bengali; button toggles between "Translate" and "Show original".

---

## 6. Notice Posting & AI Compose

### Posting Channels:
1. **Web (`NoticeComposer`)**:
   - Accessible to Owner, active Batch CR, or Teacher assigned to a course.
   - Tabs:
     - **AI compose** (Default for Owner and CR): Natural language messy input (English or Bengali). AI parses course, dates, and intent.
     - **General notice**: Title, message, course picker (if teacher without general post rights).
     - **Class cancellation**: Course picker, day selector (Today or Tomorrow).
2. **Android (`PostNoticeActivity.kt`)**:
   - Segments:
     - Normal Notice / AI Posting Mode.
     - Class Cancellation (prompts course and date picker).
     - Vacation / Notice details.
     - Image & PDF Attachment picker (validates image type JPG/PNG/WebP, max 10MB).
   - Polishing with AI: `AIService.analyzeAndPolishNotice` cleans up messy drafts and suggests subject/type.

### The `/silent` Directive
- Typing `/silent` at the beginning of a notice title or body:
  - Database trigger `normalize_silent_notice` strips the `/silent` keyword and sets `silent = true` in `classmate.notices`.
  - Trigger `enqueue_notice()` checks `if new.silent then return new; end if;`.
  - **Effect**: Notice is published directly into the feed, but **no push notifications (FCM or Web Push)** are dispatched into `notification_outbox`.

---

## 7. Role & Authorization Rules

| Role | Reading Feed | Posting Permissions | Special Rules |
| :--- | :--- | :--- | :--- |
| **Student** | Reads batch notices | None | Can like, comment, seen, bookmark, translate |
| **CR (Class Rep)** | Reads batch notices | Can post general notices & cancellations to their assigned batch | Scoped strictly to their own batch (`cr_batch_id == batch_id`) |
| **Teacher** | **Only sees their own posted notices** (`author_id = teacher_id`) | Can post notices for courses they are assigned to | No student unread badges or onboarding prompts |
| **Admin / Owner** | Reads all batches | Can post anywhere, edit/delete any notice, bypass restrictions | Full system oversight |

---

## 8. Backend RPC & Database Schema Reference

### Core Table: `classmate.notices`
- `id` (uuid, PK)
- `department_id` (uuid, FK)
- `batch_id` (uuid, FK)
- `semester_course_id` (uuid, FK, nullable)
- `author_id` (uuid, FK `classmate.profiles`)
- `resource_id` (uuid, FK `file_metadata`, nullable)
- `class_change_id` (uuid, FK `class_changes`, nullable)
- `title` (text, not null)
- `body` (text, not null default '')
- `published_at` (timestamptz, default now())
- `silent` (boolean, default false)

### Key Database RPCs:
- `post_notice(target_batch, target_course, notice_title, notice_body)`: Creates notice and queues notification outbox.
- `post_cancellation_notice(target_batch, target_course, change_date)`: Creates both `class_changes` record and cancellation notice.
- `edit_notice(target_id, target_title, target_body)`: Updates title/body; preserves author permissions.
- `delete_notice(target_id)`: Removes notice and associated comments/reactions.
- `notice_feed_details(target_ids)`: Materialized batch query returning JSON with:
  - `engagement`: `like_count`, `comment_count`, `is_liked`, `is_pinned`, `reminder_at`
  - `authors`: `author_name`, `avatar_url`
  - `reads`: `read_count`, `read_by_me`
  - `previews`: Top 4 reader profiles (`profile_id`, `reader_name`, `avatar_url`, `read_at`)
- `mark_notices_read(target_ids)`: Inserts rows into `classmate.notice_reads`.
- `notice_readers_page(target_notice, before_time, before_id, page_size)`: Keyset pagination for reader list dialog (default 50).
- `comment_page(target_notice, target_parent, before_time, before_id)`: 50-item keyset pagination for threaded comments.
- `save_notice_comment(...)`, `delete_notice_comment(...)`, `set_comment_like(...)`.

---

## 10. Notice Badges, Admin Privacy & Instant UI Mechanics (New Architecture)

### A. Notice Tab Unread Badge Behavior
- **Trigger**: The notices tab icon badge in bottom navigation (`GlassBottomNavView` / `ClassMateUnreadActivity`) only displays when there is at least one published notice in the user's batch that has **not** been read by the user.
- **Immediate Dismissal**: As soon as the user opens the notices tab and views visible notices (or scrolls through all unread notices), `markVisibleNoticesRead` invokes `onNoticeRead()`.
- **0ms Badge Clearing**: `ClassMateUnreadActivity.clearNoticeBadge()` is immediately executed to clear the badge locally without awaiting a network roundtrip, followed by background synchronization.

### B. Admin Read Receipts Privacy Toggle
- **Database & Preferences**: Controlled by `owner_preferences.read_receipts_enabled` (backed by `AppPreferences.isNoticeReadReceiptsEnabled()` on Android and query `owner-preferences` on Web).
- **Backend Enforced Privacy** (`supabase/migrations/202610070002_notice_badge_and_admin_privacy.sql`):
  - When an admin has turned off read receipts (`read_receipts_enabled = false`):
    - `mark_notices_read` still records the read in `classmate.notice_reads` so the admin's personal `unread_activity` accurately drops to 0 (clearing their badge).
    - However, `notice_feed_details`, `notice_reader_previews`, and `notice_readers_page` explicitly filter out any reader whose profile is an admin owner with `read_receipts_enabled = false`.
    - The public `read_count` excludes reads by admins who opted out.
- **Client-Side Enforced Privacy**:
  - Android: `bindSeenAvatar` and `showNoticeReadReceipts` filter out the admin's profile if `profile().optString("role") == "admin" && !appPrefs.isNoticeReadReceiptsEnabled()`.
  - Web: `NoticeCard` (avatar preview list) and `ReaderList` modal filter out the admin profile when `ctx.owner && receiptsEnabled.data === false`.

### C. Instant 0ms Optimistic Notice Posting
- **Android**: `ClassMatePublishActivity` passes the newly created notice JSON back in `result.data.getStringExtra("created_notice")` with `RESULT_OK`. `ClassMateAuthActivity` retrieves it and invokes `academicScreens.onNoticeCreated(json)`, which immediately prepends the notice to `noticeFeed`, updates the local cache, and renders the feed in 0ms without full-screen loading spinners.
- **Web**: `NoticeComposer` returns the created notice from `post_notice` / `post_cancellation_notice`. The `done(created)` callback prepends it directly to TanStack Query's infinite query cache (`pages[0].entries`) and invalidates queries in the background.

### D. Instant Read Receipts & Initial Layout Marking
- **Initial Layout Trigger**: When opening the notices tab, `markVisibleNoticesRead` is triggered via `list.post` on initial layout or if `list.height == 0`, ensuring notices visible upon opening are marked as read without requiring user scrolling.
- **Optimistic State**: Read counts and avatars update in-memory (`noticeReadCounts`, `noticeReaderPreviews`) and cache immediately upon view.

---

## 11. Premium Facebook-Style Comment System Architecture

### A. Core UX & Modal Design
- **Separate Popup Layout**:
  - **Android**: Displays as an interactive `BottomSheetDialog` using `R.style.Theme_ClassMate_BottomSheetDialog` (with `WRAP_CONTENT` / peek behavior and soft top rounded corners). Contains a drag handle, pinned header with notice title and comment count pill, and an `OnBackPressedCallback` / close cross.
  - **Web**: Modern Facebook modal overlay (`.comments-modal-shell`, `.comments-modal-container`, `max-w-2xl`) with backdrop blur, pinned header, scrollable body (`.comments-body-scroll`), and sticky bottom composer.
- **Visual Facebook Aesthetics**:
  - **Speech Bubble Design**: Comments and replies are encased in distinct bubble backgrounds (`bg_comment_bubble.xml` with `#F1F5F9` surface, soft borders, and 18dp corner radii).
  - **Author Badges**: Notice authors have a distinct visual badge ("Author" / "Admin") rendered beside their name in both parent comments and replies.
  - **Engagement Row**: Directly below each bubble is a compact engagement row featuring timestamp, `Like` button with optimistic count increment, `Reply` button, and context menu trigger.
  - **Floating Heart / Like Counter**: Active likes display a floating heart counter badge (`❤️ count`) positioned on the comment bubble.

### B. Compact Bottom Composer System
- **Size & Layout**:
  - Engineered with a compact capsule design (`bg_comment_input_capsule.xml` on Android, `.comment-sticky-footer` on Web).
  - Web textarea is capped with `max-height: 110px`, `min-height: 40px`, and auto-resizing.
  - Android `EditText` has `maxLines="3"` with `inputType="textCapSentences|textMultiLine"`.
  - Accompanied by a circular send button with smooth enable/disable states based on text length.
- **Contextual Reply Banner**:
  - When tapping "Reply" on any comment, a slim contextual banner appears above the input capsule: `"Replying to [Name]"` with a quick cancel (`✕`) button.
  - Submitting automatically clears the reply target and routes to the appropriate thread.

### C. Threaded Reply Trees (Inline Branching)
- **Nested Hierarchy Without Secondary Dialogs**:
  - Rather than opening nested popups for child comments (which fragment context), replies are displayed inline directly beneath the parent bubble.
  - Indented tree structure with visual branching guides (`.reply-thread` with vertical line on Web; nested `LinearLayout` with left border indentation on Android).
  - **"View X replies" / "Hide replies" Toggle**: Collapsible reply toggle preserves clean feed scanning. Tapping fetches child comments via `comment_page(target_notice, parent_id)` and loads them smoothly.
- **Edit & Delete Action Menus**:
  - Authors and Admins have quick access via 3-dot popup menu (`PopupMenu` on Android, dropdown on Web) to edit comment text or delete comments.

---

## 12. Self-Hosted In-App Update Subsystem (GitHub Releases & PackageInstaller)

This section documents the non-Play Store update pipeline, client-side security verification, download resumption mechanics, and UI presentation.

### A. High-Level Architecture & Asset Distribution
- **Distribution CDN**: ClassMate uses the public GitHub repository releases (`shuaibuddowla/ClassMate/releases/latest/download`) to distribute updates over HTTPS without custom server or S3 hosting fees.
- **Release Assets**:
  - `update.json`: Lightweight manifest containing `versionCode`, `versionName`, `minSupportedVersionCode`, `apkUrl`, `apkSize`, `sha256`, `mandatory`, `releaseDate`, and `releaseNotes`.
  - `classmate-<versionName>.apk`: Immutable production APK signed with keystore alias `key0`.
- **Publishing Pipeline (`publish-classmate-github-update.ps1`)**:
  - Pre-publication checks: git dirty state, remote push alignment, `aapt` package and version verification (`com.shuaib.classmate`), `apksigner` certificate fingerprint matching `keystore.properties` (`releaseCertSha256`), and presence of updater configuration in DEX bytecode.
  - Draft-first publishing: Creates draft release, uploads versioned APK first, uploads `update.json` last, verifies assets and SHA-256 digests on GitHub, then marks the release as latest.
  - Automated FCM Push Broadcast: Invokes `notify-classmate-release.ps1` -> Supabase Edge Function `publish-classmate-release` with `x-dispatch-secret`, immediately notifying active devices across batches without waiting for periodic polling.

### B. Client Security & Cryptographic Integrity (`ApkVerifier.kt`, `UpdateSafety.kt`)
Before any installation session is committed, the APK candidate is verified strictly:
1. **Size & SHA-256 Digest**: Exact byte count and SHA-256 hash match against `update.json`.
2. **Package Name & Version Code**: Verifies `archive.packageName == context.packageName` and `candidateCode > installedCode`. Downgrade attacks are rejected.
3. **Certificate & Key Rotation History**:
   - Inspects `signingInfo.apkContentsSigners` against the installed app's signer.
   - On Android 9+ (API 28+), inspects `signingCertificateHistory` to support cryptographic signing key rotation without breaking update continuity.
   - Rejects debug keys or mismatched certificate fingerprints.

### C. Resilient HTTP Range Resumption (`UpdateRepository.kt`)
- **Staging Storage**: APK is downloaded into private app storage (`context.filesDir/updates/classmate-update.tmp`).
- **Resumption (`HTTP 206 Partial Content`)**:
  - If a download drops due to flaky mobile networks or battery saver interrupts, the temp file is preserved.
  - On retry, sends `Range: bytes=<existingBytes>-`.
  - If the server accepts with `HTTP 206`, chunks append to `classmate-update.tmp`. If `HTTP 200`, it cleanly starts from byte 0.
- **Integrity Finalization**:
  - When byte count equals `metadata.apkSize`, it renames to `classmate-<versionCode>.apk`.
  - Temporary files are only destroyed if a permanent integrity error (`PermanentUpdateException`) is raised.

### D. Modern Android PackageInstaller & Unattended Updates (`ApkInstaller.kt`)
- **Session-Based Installation**: Rather than exposing raw `file://` or `content://` intents to external package installers, ClassMate streams APK chunks into Android's `PackageInstaller` session with `fsync`.
- **Android 12+ (API 31+) Unattended Updates**:
  - Manifest declares `<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />`.
  - `ApkInstaller` sets `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. If the app was the installer or satisfies platform ownership requirements, the update commits silently in the background without user interruption.
- **Fallback Callback (`InstallResultReceiver.kt`)**:
  - If Android requires confirmation, `STATUS_PENDING_USER_ACTION` is received with `Intent.EXTRA_INTENT`.
  - If app is visible, opens `UpdateActionActivity.ACTION_CONFIRM`. If in background, alerts via system notification.
  - Upon `STATUS_SUCCESS`, caches and temp APK files are cleaned up.

### E. Rich Custom UI & In-App Lifecycle (`UpdateActionActivity.kt`, `dialog_app_update.xml`)
- **Custom Card UI**: Inflates `dialog_app_update.xml` inside a rounded `MaterialCardView` with translucent dialog window:
  - **Glowing Header & ClassMate Logo**: `bg_update_circle_glow` and `bg_update_circle_gradient`.
  - **Version Transition Pills**: Shows current version (`tvLocalVersionPill`, e.g. `v1.1.24`) transitioning to new version (`tvUpdateVersion`, e.g. `v1.1.25`).
  - **Scrollable Changelog**: Formatted bullet points from `metadata.releaseNotes` in `tvUpdateChangelog`.
  - **Live Download Progress**: In-card horizontal progress bar (`pbDownloadProgress`) with dynamic percentage and downloaded MB readout (`tvDownloadProgressText`).
  - **Action Buttons**: "Later" (`btnUpdateLater`) and "Update Now" / "Install Now" (`btnUpdateNow`).
- **Decoupled Manual Check**:
  - Profile screen "Check for Updates" executes `ACTION_CHECK`.
  - Only fetches lightweight `update.json` (~1 KB). Does **not** download 30MB APK prematurely.
  - Shows "You're up to date" if on latest version, or presents the custom dialog with download size and release notes if an update exists.
- **Mandatory Update Enforcement (`ACTION_MANDATORY`)**:
  - Triggered if `metadata.mandatory == true` or `installedCode < metadata.minSupportedVersionCode`.
  - Immediately persists `pendingMandatoryVersionCode`.
  - Removes the "Later" button, disables outside touch dismiss, and intercepts Back button presses (`onBackPressedDispatcher`) to exit the app (`finishAffinity()`), preventing users from bypassing breaking API updates.



---

## 13. Timetable & Routine Homepage Subsystem Reference

### A. Dual Architecture & Core Components
1. **Modern Supabase Academic Screens (`ClassMateAcademicScreensSupabase.kt`)**: Production architecture in ClassMate 1.1.23+ (`R.id.nav_timetable`).
   - 3-mode segmented toggle: `Class Routine`, `Bus Schedule`, and `Academic Calendar` (`ClassMateCalendarUi`).
   - Dynamic 7-day selector with red indicator/border when an academic calendar closure or holiday is active (`ClassMateCalendarData.classClosure(date)`).
   - Fast offline caching via `ClassMateAcademicCache` (`"routine"` / `"bus"`).
   - Server-authorized editing via RPC `can_manage_routine` and `save_routine_slot`.
2. **Companion / Hybrid Screens (`TimetableFragment.kt`, `PeriodAdapter.kt`, `TimetableViewModel.kt`)**:
   - Room DB reactive flows, Live Class Hero card (`heroNextClass`) with real-time countdown progress bar, and academic calendar exception banners (`calendarExceptionBanner`).
   - WorkManager `TimetableResetWorker` and midnight refresh handlers resetting yesterday's cancellations at 00:00:01.
3. **Web Client Parity (`web/src/components/schedule.tsx`)**:
   - Responsive routine list with Dhaka timezone day rollover, interactive month calendar, teacher assignments filtering, and modal slot editor.

### B. Dynamic Live Class Badge in Period Rows
- **Placement & Replacement**:
  - The badge pill (`tvTypeBadge` in Android, `.period-tag` in Web) is positioned on the right side of each period row.
  - When a period is **actively live** (viewing today's date, period is not cancelled, and `startTime <= currentTime < endTime`):
    - The default `"CLASS"` or `"LAB"` badge is **replaced** with **`"LIVE NOW"`** (or `"Live now"` on Web).
    - **Visual Styling**:
      - Background: Emerald green accent pill (`bg_badge_green` on Android, `.period-tag.live` on Web).
      - Text Color: Green text (`@color/cm_file_lab_text` on Android, `var(--green)` on Web).
- **Post-Class Time Automatic Reset**:
  - The moment the clock reaches or passes `endTime` (`currentTime >= endTime`), the badge **automatically resets** back to its default:
    - If Lab: `"LAB"` (`bg_badge_green`, `cm_file_lab_text`).
    - If Regular Class: `"CLASS"` (`bg_badge_blue`, `cm_primary`).
  - If a period was cancelled for today: it retains the red `"CANCELLED"` badge with cancellation background, taking precedence over the live badge.
- **Dynamic Background Tickers**:
  - **Android V2 (`ClassMateAcademicScreensSupabase.kt`)**: Schedules a lightweight 15-second tick runnable on `root` when viewing today's routine in foreground. Calls `rvPeriods.adapter.notifyDataSetChanged()` to smoothly flip between `LIVE NOW` and `CLASS`/`LAB` without page reload.
  - **Android Hybrid (`TimetableFragment.kt`)**: The 1-second countdown loop monitors `currentLivePeriods` against `lastLivePeriodIds`, notifying `PeriodAdapter` immediately upon time transitions.
  - **Web (`schedule.tsx`)**: 10-second interval updates `currentTime` state, triggering instant reactive re-render of period badges when classes start or end.

### C. Cancellation & Notice Interconnection
- When a class cancellation notice is published via `post_cancellation_notice`, a record is inserted into `classmate.class_changes`.
- Timetable projection RPC `timetable_cancellations` returns the affected date and offering ID.
- `PeriodAdapter` and modern routine rows turn red with `"CANCELLED"` badge, the header count updates to `"X classes · Y cancelled"`, auto-mute skips the period, and the live hero card advances to the next non-cancelled class.
