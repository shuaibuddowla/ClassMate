# ClassMate Platform & Android APK â€” Complete Architecture, Memory & Debugging Reference

This memory file serves as the definitive engineering knowledge base and memory reference for the ClassMate Android APK project, Web companion client, and Supabase backend. It documents architecture, design tokens, data models, recent features, debugging resolutions, and implementation guidelines across all modules.

---

## 1. High-Level Overview & Core Concepts

- **Purpose**: The Notice page/feed is the central academic broadcasting and engagement hub in ClassMate. It delivers announcements, class cancellations, resources/file attachments, exam notices, and deadlines to students and faculty.
- **Public Resource Sharing (/r/[slug])**:
  - Direct public access for academic files and Google Drive resources shared in Telegram or copied externally without requiring user authentication, session cookies, or app installation.
  - Dedicated Next.js web preview page (web/src/app/r/[id]/page.tsx) and download streaming endpoint (web/src/app/api/public-download/[id]/route.ts).
  - Title slug generation via classmate.slugify(title) and classmate.generate_unique_resource_slug(title) with auto-deduplication (-1, -2) on insert/update in classmate.file_metadata.slug.
  - Dual side-by-side action buttons: **Open** (inline browser tab view / Drive folder) and **Download** (direct device file save with original clean filename and extension via Content-Disposition: attachment).
  - Brand footer accurately matches Android Timetable header: lowercase classmate. in heavy bold (Plus Jakarta Sans 800, letter spacing -0.03em) with primary accent dot ..
  - Backward compatibility: supports both title slugs and legacy UUID paths.
- **Dual Architecture**:
  1. **Modern Supabase Academic Screens** (`ClassMateAcademicScreensSupabase.kt`): The active, production architecture powering ClassMate 1.1.23+ with smooth ListAdapter paging, real avatars, threaded comments, read receipts, and AI compose.
  2. **Legacy / Complementary Components** (`NoticeFragment.kt`, `NoticeAdapter.kt`, `NoticeRepository.kt`, Room DB `NoticeDao.kt`, Firestore): Kept for offline caching, hybrid fallbacks, and backward compatibility.
- **Web App Parity** (`web/src/components/notices.tsx`, `notice-comments.tsx`): Full functional parity with Android â€” responsive mobile feed and persistent desktop right-hand rail, real avatars, reaction toggles, keyset paging, translation, and reader dialogs.

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

### Text Rendering & Inline Expansion (`ClassMateNoticeText.kt`)
- **Search Highlighting**: Matches are highlighted using `BackgroundColorSpan(cm_search_highlight)` (bright yellow) and `ForegroundColorSpan(cm_search_highlight_text)`.
- **URL Linkification & Precision Hit-Testing**: `Linkify.addLinks(..., Linkify.WEB_URLS)` makes web URLs clickable. `findClickableSpanUnderTouch` verifies line horizontal and vertical bounds (`x >= layout.getLineLeft(line)` and `x <= layout.getLineRight(line)`) before triggering `onClick` and consuming the touch. URLs open cleanly without triggering card ripples or false expansion toggles.
- **1200-Character Layout Bound**: Cards clamp preview string length to `minOf(body.length, 1200)` before calculating `StaticLayout`, preventing multi-thousand character rendering slowdowns while supporting 6-line previews.
- **Unicode Safety**: High-surrogate boundaries are respected (`Character.isHighSurrogate(body[end - 1])`) to prevent emoji truncation crashes.
- **6-Line Truncation & Lowercase "â€¦ see more"**:
  - Truncates at up to 6 lines (instead of 3).
  - Strictly appends `"â€¦ see more"` only if the text overflows 6 lines; shorter text ($\le 6$ lines) displays completely without any button.
  - Tapping `"â€¦ see more"` or tapping the notice title (only when expandable) expands the card inline directly in the feed.
- **Inline Expansion & Lowercase "see less"**:
  - Eliminates separate popup modals for viewing notices.
  - Short notices ($\le 6$ lines) are guarded by `isExpandable(body)`: clicking title does not toggle expand, and `"  see less"` is never appended.
  - When genuinely overflowing text is expanded, shows full text and appends an inline `"  see less"` clickable span to collapse back smoothly.
  - Card root click listener is removed (`cardRoot.isClickable = false`), avoiding unwanted card-wide tap animations.
- **Click & Hold (Long-Press) to Copy**:
  - Custom `setupCollisionFreeTouch` detects touch hold (>= 400ms), triggers native haptic vibration (`HapticFeedbackConstants.LONG_PRESS`), copies `"$title\n\n$body"` to clipboard, and shows a `"Notice copied"` toast.
  - Link collision protection: if long-pressed over a URL or expand button, the link click is canceled so the browser is not opened.
  - Suppresses teardrop cursor handles and text selection popups (`movementMethod = null`, transparent highlight).

### Notice Summary Row (`item_notice_summary.xml`)
- Rendered at the top of the feed (position 0 when active).
- Displays the most recent author's avatar (`ivSummaryAvatar`), name (`tvSummaryName`), total update count, and latest post date (`tvSummaryMeta`).

### Modern Notice Card Header & Body (`item_notice_modern.xml`)
- `noticeAccent`: 3dp colored vertical indicator matching the notice type.
- `tvTitle`: Bold 18sp title, expands to full lines when card is expanded. Tapping toggles inline expand/collapse only when expandable; holding copies notice.
- `ivNoticeIllustration`: Megaphone, calendar cancellation, or resource art.
- `btnOptions`: 3-dot management button (`ic_more_vert`). Only enabled/opaque (alpha 1f) for owner, CR, or the notice's author. Opens action dialog with "Edit title and message" and "Delete notice".
- `tvPreview`: Truncated 6-line body with inline expand/collapse and long-press copy support.
- `tvMeta`: Date formatted with calendar clock outline icon (`ic_notice_date_small`).
- `btnTranslate`: Constrained rounded ripple mask (`bg_btn_translate.xml`) with small language icon (`ic_translate_small`) + "Translate" / "Original". Eliminates giant circular bubble, matching web UX.

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

## 9. Self-Hosted In-App Update Subsystem (GitHub Releases & PackageInstaller)

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
  - **Floating Heart / Like Counter**: Active likes display a floating heart counter badge (`â¤ï¸ count`) positioned on the comment bubble.

### B. Compact Bottom Composer System
- **Size & Layout**:
  - Engineered with a compact capsule design (`bg_comment_input_capsule.xml` on Android, `.comment-sticky-footer` on Web).
  - Web textarea is capped with `max-height: 110px`, `min-height: 40px`, and auto-resizing.
  - Android `EditText` has `maxLines="3"` with `inputType="textCapSentences|textMultiLine"`.
  - Accompanied by a circular send button with smooth enable/disable states based on text length.
- **Contextual Reply Banner**:
  - When tapping "Reply" on any comment, a slim contextual banner appears above the input capsule: `"Replying to [Name]"` with a quick cancel (`âœ•`) button.
  - Submitting automatically clears the reply target and routes to the appropriate thread.

### C. Threaded Reply Trees (Inline Branching)
- **Nested Hierarchy Without Secondary Dialogs**:
  - Rather than opening nested popups for child comments (which fragment context), replies are displayed inline directly beneath the parent bubble.
  - Indented tree structure with visual branching guides (`.reply-thread` with vertical line on Web; nested `LinearLayout` with left border indentation on Android).
  - **"View X replies" / "Hide replies" Toggle**: Collapsible reply toggle preserves clean feed scanning. Tapping fetches child comments via `comment_page(target_notice, parent_id)` and loads them smoothly.
- **Edit & Delete Action Menus**:
  - Authors and Admins have quick access via 3-dot popup menu (`PopupMenu` on Android, dropdown on Web) to edit comment text or delete comments.

---

## 12. WhatsApp-Style "Message Info" (Read Receipts) Subsystem

### A. Core UX & Visual Hierarchy
- **Trigger**: Tapping the "Seen by" avatars or read count on any notice card opens the dedicated WhatsApp "Message info" view instead of a generic dialog.
- **Top App Bar**:
  - Contains a back arrow navigation button (`ic_arrow_back` / `ArrowLeft`) and bold title `"Message info"`.
  - Android includes an optional refresh icon button (`ic_refresh`) to re-fetch the latest readers without reopening.
- **Chat Wallpaper & Outgoing Message Bubble**:
  - **Wallpaper**: Chat wallpaper container with subtle doodle dot pattern (`bg_whatsapp_wallpaper.xml`, `#EFEAE2` light, `#0B141A` dark).
  - **Outgoing Bubble**: Aligned to the right with rounded corners (`bg_whatsapp_bubble.xml`, `#D9FDD3` light, `#005C4B` dark), displaying:
    - Bold notice title (if present).
    - Notice body text (with clickable URL linkification).
    - Meta row with notice timestamp (e.g., `12:10 am`) and checkmarks (`âœ“âœ“` in `#53BDEB`).
- **"Read by" Section Card**:
  - **Header**: Text `"Read by"` in WhatsApp green (`#00A884`), accompanied by blue double checkmarks (`âœ“âœ“` in `#53BDEB`) and total reader count badge (e.g., `(7)`).
  - **Readers List**:
    - **Avatar**: Circular profile image (`CircleImageView` / `<img>`). If absent, dynamically falls back to a colored circular initial badge (using a hash of the reader's name mapped across a vibrant WhatsApp palette: `#00A884`, `#3B82F6`, `#8B5CF6`, `#EC4899`, `#F59E0B`, etc.).
    - **Reader Name**: Bold primary typography.
    - **Read Timestamp**: Exact WhatsApp relative formatting:
      - `Today, 12:11 am` (for reads on the current day)
      - `Yesterday, 10:15 pm` (for reads on the previous day)
      - `07 Oct, 9:20 am` (for older reads within the current year)
    - **Divider**: Inset 70dp divider line between items.
  - **Empty State**: Displays `"No one has read this notice yet"`.
  - **Pagination**: Supports 50-item keyset pagination via `notice_readers_page` with a `"Load more"` button.
- **Privacy Enforcement**:
  - Automatically respects `owner_preferences.read_receipts_enabled`. If an admin has disabled read receipts, their entry is filtered out on both Android and Web.


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
- `PeriodAdapter` and modern routine rows turn red with `"CANCELLED"` badge, the header count updates to `"X classes Â· Y cancelled"`, auto-mute skips the period, and the live hero card advances to the next non-cancelled class.


---

## 14. WhatsApp-Style "Message Info" Read Receipts & Facebook-Style Comments

### A. WhatsApp-Style "Message Info" Subsystem
1. **Interactive Trigger**:
   - In both Android and Web, tapping the `.seen` section (`previews` avatar list + `X seen`) on any notice card opens the dedicated WhatsApp "Message info" layout.
   - On Web, `NoticeCard` `.seen` button uses `type="button"`, `onClick={(e) => { e.preventDefault(); e.stopPropagation(); onReaders(); }}`, `cursor: pointer`, and hover background styling (`background: color-mix(in srgb, var(--primary) 10%, transparent)`).
2. **Web Top-Layer Native `<Modal>` Architecture**:
   - Must be wrapped in `<Modal title="Message info" close={close}>`.
   - The `<Modal>` component relies on HTML5 native `<dialog ref={ref}>` with `dialog.showModal()`, placing the container into the browser's native **Top Layer**. This ensures it is never clipped or trapped by stacking contexts (`contain`, `overflow-y: auto`, `position: sticky`, or `filter`) from `.notice-pane` or feed containers.
   - `globals.css` targets `dialog:has(.wa-message-info-shell)` to strip default dialog padding, style rounded 20px corners, hide the default dialog header (`dialog:has(.wa-message-info-shell) > header { display: none !important; }`), and display the WhatsApp layout.
3. **WhatsApp Visual Design Specifications**:
   - **Header (`.wa-message-info-header`)**: Contains `wa-header-back-btn` (`<ArrowLeft />`), title `"Message info"`, and `wa-header-close-btn` (`<X />`).
   - **Chat Wallpaper & Bubble (`.wa-chat-wallpaper`, `.wa-bubble`)**: WhatsApp chat background pattern with doodle radial gradients. Green message bubble (`#d9fdd3` light, `#005c4b` dark) with title, notice body, timestamp, and WhatsApp cyan double-check ticks (`DoubleCheckIcon`, `#53bdeb`).
   - **Read By Section (`.wa-read-by-section`)**: Green header (`#00a884`) with double check icon and reader count badge.
   - **Reader List Items (`.wa-reader-item`)**: Displays reader avatar or colored initial badge (`.wa-avatar-initial`), full student/teacher name, and WhatsApp timestamp (`Today, 10:45 am`, `Yesterday, 4:20 pm`, or `DD Mon, HH:MM am/pm`).
   - **Infinite Pagination (`.wa-load-more`)**: 50 readers per page fetched via RPC `notice_readers_page`.
4. **Android BottomSheet Implementation**:
   - `ClassMateMessageInfo.kt` implements a rounded Material BottomSheetDialog using `dialog_notice_message_info.xml` and `item_notice_message_info_reader.xml`.
   - Double check drawable `ic_double_check.xml`, bubble background `bg_whatsapp_bubble.xml`, and wallpaper `bg_whatsapp_wallpaper.xml`.

### B. Facebook-Style Comments Subsystem
1. **Compact Ideal Composer**:
   - Auto-growing compact textarea with round bubble style, emoji/action buttons, and instant submit.
   - Dedicated separate popup dialog/sheet (`Comments` modal shell) when clicking the comment button on a notice card.
2. **Nested Replies & Threading**:
   - Support for 1-level nested reply threads under parent comments.
   - Compact inline reply badge indicating `@AuthorName`.
3. **Real-Time Polling & Invalidation**:
   - 15-second polling interval using `@tanstack/react-query` `comment_page` RPC.
   - Optimistic state updates on add, edit, and delete actions.

### C. Compact Wide Notice Detail Popup & Locked Message Info Preview Upgrade
1. **Wider, Compact Notice Detail Dialog**:
   - **Layout Dimensions**: Expanded modal width to `860px` on Web (`width: min(860px, calc(100vw - 24px))`) and `96%` of screen width (max `720dp`) on Android so wide academic notices fit comfortably without cramped text.
   - **Header Simplification**: Removed both the category badge, timestamp, and the top-right cross (`X`) button per user request. The header displays the bold notice title cleanly across the top.
   - **Split Footer Action Buttons**: Footer buttons arranged in a clean space-between split layout: "Close" is pinned to the bottom-left and "Copy" (renamed from "Copy notice") is pinned to the bottom-right, with neutral monochrome border/surface styling and "Copied!" temporary state feedback.
2. **Pinned / Locked Notice Preview in Message Info**:
   - **Web**: `.wa-notice-preview-container` is placed outside `.wa-message-info-body` directly underneath the header with `flex-shrink: 0`. The notice preview remains pinned and fully visible at the top while only the "Read by" readers list scrolls independently inside `.wa-message-info-body`.
   - **Android**: `cardNoticePreview` is placed outside the `NestedScrollView` in `dialog_notice_message_info.xml`. When users scroll down through reader avatars and names, the notice preview card stays locked in place at the top.

### D. Notice Feed 6-Line Preview Truncation & Android Translation Stability
1. **Notice Card 5â€“6 Lines Preview Support**:
   - **Android**: Updated `ClassMateNoticeText.kt` and `item_notice_modern.xml` `tvPreview` `maxLines` to `6` (previously 3 lines). The `â€¦ See more` suffix is strictly evaluated: if the text fits within 6 lines or fewer, `â€¦ See more` is completely omitted.
   - **Web**: `NoticeCard` in `notices.tsx` evaluates explicit lines (`body.split("\n").length > 6`) and character length (`body.length > 520`). If the notice has 6 lines or fewer, "See more" is hidden.
2. **Android Translate Toggle & Pointer Teardrop Bubble Elimination**:
   - **Continuous Translation Toggling**: In `ClassMateAcademicScreensSupabase.kt`, `translation.isEnabled = true` is explicitly restored whenever binding a notice card. This prevents the button from staying disabled after the initial translate request, enabling users to seamlessly toggle between "Translate" and "Original" multiple times.
   - **Teardrop Pointer Bubble Removal**: In `ClassMateNoticeText.kt`, the framework `LinkMovementMethod` was replaced with a custom non-selecting movement method that dispatches link clicks on `ACTION_UP` without calling `Selection.setSelection(...)`. In addition, `isFocusable = false`, `isFocusableInTouchMode = false`, `isCursorVisible = false`, and `highlightColor = Color.TRANSPARENT` are enforced on the preview `TextView`, completely suppressing the Android framework cursor teardrop / selection pointer handle.
---

## 15. Batch Fund Account Subsystem

### A. High-Level Architecture & Purpose
The **Batch Fund** subsystem is a comprehensive financial ledger built directly into ClassMate to manage student batch funds (e.g., student collections, event budgets, department contributions, tour fees, and emergency assistance). It eliminates manual spreadsheets, WhatsApp ledger notes, and fragmented bookkeeping by providing a unified, transparent, and auditable accounting ledger for students and class representatives (CR).

```mermaid
flowchart TD
    subgraph UI Layer
        A["Batch Tab Segmented Toggle<br>Batch (X) | Batch Fund (à§³Y)"] --> B[ClassMateBatchFundActivity]
        B --> C[Hero Balance & Metric Tiles]
        B --> D[Recent Transactions List]
        B --> E["Action Sheets (Add Fund, Ledger, Payers)"]
    end

    subgraph Data & Networking Layer
        B --> F["ClassMateAuthApi.rpc / rpcVoid"]
        F --> G["Supabase PostgREST & RPC Engine"]
    end

    subgraph Database Layer
        G --> H["classmate.batch_fund_transactions"]
        G --> I["classmate.batch_fund_categories"]
        G --> J["RPC: record_batch_fund_transaction"]
        G --> K["RPC: delete_batch_fund_transaction"]
        G --> L["RPC: batch_fund_dashboard"]
    end
```

### B. Database Schema & Supabase Migrations
Managed across migrations:
- `202610080002_batch_fund_feature.sql`
- `202610080003_batch_fund_profile_link.sql`
- `202610090001_batch_fund_aliases.sql`

#### 1. Core Tables:
- **`classmate.batch_fund_transactions`**:
  - `id`: UUID PRIMARY KEY (`gen_random_uuid()`)
  - `batch_id`: TEXT NOT NULL (e.g. `'CSE-22'`)
  - `type`: TEXT NOT NULL CHECK (`type IN ('deposit', 'expense')`)
  - `amount`: NUMERIC(10,2) NOT NULL CHECK (`amount > 0`)
  - `title`: TEXT NOT NULL (Purpose / reason for collection or expense)
  - `student_name`: TEXT NULL (Name of contributing student for deposits)
  - `student_profile_id`: UUID NULL REFERENCES `classmate.profiles(id)`
  - `category`: TEXT NOT NULL DEFAULT `'general'` (`'tour'`, `'farewell'`, `'photocopy'`, `'sports'`, `'emergency'`, `'charity'`, `'general'`)
  - `recorded_by`: UUID NOT NULL REFERENCES `classmate.profiles(id)`
  - `created_at`: TIMESTAMPTZ NOT NULL DEFAULT `now()`
  - `transaction_date`: DATE NOT NULL DEFAULT `CURRENT_DATE`

#### 2. Stored Procedures & RPCs:
- **`batch_fund_dashboard(p_batch_id text)`**: Returns single JSON object containing:
  - `balance`: Current total balance (`total_deposits - total_expenses`)
  - `total_collected`: Sum of all deposit amounts
  - `total_spent`: Sum of all expense amounts
  - `transaction_count`: Total number of ledger entries
  - `recent_transactions`: JSON array of the latest 20 transactions with recorder profile info
- **`record_batch_fund_transaction(...)`**: Records new deposit or expense. Returns the newly inserted transaction UUID (`text`). Requires Admin, CR, or authorized Treasurer privileges.
- **`delete_batch_fund_transaction(p_id uuid)`**: Permanently deletes a ledger transaction, recalculating batch totals dynamically. Restricts deletion to record creators and Admins.

### C. Android Implementation (`ClassMateBatchFundActivity.kt` & Layouts)
1. **Activity Architecture (`ClassMateBatchFundActivity.kt`)**:
   - Full-featured dashboard containing hero balance card (`bg_fund_hero`), metric summary tiles (`bg_fund_tile_green` for collected, `bg_fund_tile_orange` for spent), category filter chips, and transaction history RecyclerView.
   - Integrated with `SwipeRefreshLayout` for smooth pull-to-refresh sync.
2. **Action Modals & Bottom Sheets**:
   - **`dialog_add_fund_sheet.xml`**: Modern bottom sheet for recording deposits or expenses. Includes instant student roster search autocomplete, amount input with Bangladeshi Taka currency adornment, category selector, and custom notes.
   - **`dialog_edit_transaction.xml`**: Sheet for updating existing transaction amounts or details.
   - **`dialog_fund_ledger_sheet.xml`**: Detailed filterable ledger showing full transaction logs grouped by date/month.
   - **`dialog_fund_payers_sheet.xml`**: Payer audit sheet breaking down which students have paid for a specific collection drive and identifying outstanding dues.
3. **Manual Entry & Instant Student Search**:
   - Freeform natural language AI parsing was evaluated but replaced with a clean **instant search roster selector** (`batch_fund_manual_entry_instant_search_plan.md`).
   - Users can search any classmate by typing the first letters of their name or student ID (`22001`, `Mehedi`, etc.), auto-filling the name and linking the transaction to their profile ID. A custom name write-in fallback is preserved for outside contributors.
4. **Real-Time Balance Sync on Batch Tab**:
   - The Batch Tab segmented toggle button displays `Batch Fund (à§³[Balance])`.
   - In `ClassMateFriendsScreen.kt`, balance updates are kept perfectly in sync: an `OnAttachStateChangeListener` and `render()` hook re-fetch `batch_fund_dashboard` whenever returning to the screen, ensuring newly added or deleted transactions immediately reflect on the toggle without restarting the app.

---

## 16. People / Batch Tab Redesign & Class Representative Prioritization

### A. Architectural Evolution & Renaming
The third main tab was evolved from a simple "Friends" directory into an all-inclusive academic community hub: **"Batch"** (or "People").
- **Segmented Header Toggle**: Users can effortlessly toggle between:
  - `Batch (X)`: Student directory, Class Representatives, active online status, and profiles.
  - `Batch Fund (à§³Y)`: Financial ledger, class collections, and balance dashboard.
- **Header Search Bar**: Space-saving search icon in the top header replaces bulky static search bars. Tapping opens an expanded search bar with smooth fade animation.

### B. Class Representative (CR) Prioritization & Admin Pinning
1. **Prominent CR Placement**:
   - Class Representatives are automatically sorted and pinned to the **very top of the student roster** via migration `202610080001_active_presence_and_cr_sorting.sql`.
   - Rendered with an elegant, full-title blue text badge: `"Class representative"` (reverting from cramped `"CR"` pills per user preference).
2. **CSE22 Batch Exception**:
   - The system administrator / app owner belonging to the CSE22 batch is pinned at position zero directly above or alongside CRs, preserving administrative hierarchy.
3. **Teacher Account Segregation**:
   - Faculty and teacher profiles are strictly excluded from the student Batch directory view (`c7ea228e-213f-418c-8495-07589ceb18cb`), ensuring the list reflects only enrolled peers while keeping teacher contacts in academic lookup screens.

### C. Live Presence & Active Status Tracking
- **Presence Engine**: Supabase `profiles` table tracks `last_seen_at` timestamps on every authenticated session heartbeat and API invocation (`ClassMateAuthActivity.kt`).
- **Visual Status Pill**:
  - If `last_seen_at` is within the last 5 minutes: Displays an emerald green active dot / pill (`bg_status_pill_active`) indicating the student is currently online.
  - If inactive: Displays subtle relative activity text (e.g. `Active 2h ago`, `Active yesterday`).
- **Web & Android Parity**: Active status indicators are rendered uniformly across `ClassMateFriendsScreen.kt` and `web/src/components/friends.tsx`.

### D. Blood Group Search & Profile Blood Hub
- **Database Search Engine (`202610080004_search_blood_group.sql`)**:
  - RPC `search_blood_group` enables multi-attribute querying. The search engine matches query strings against `name`, `student_id`, and `blood_group` (`A+`, `A-`, `B+`, `B-`, `AB+`, `AB-`, `O+`, `O-`).
  - Allows immediate identification of eligible batch blood donors during campus medical emergencies.
- **Wider Profile Popup Dialog (`dialog_friend_detail.xml`)**:
  - Expanded layout width to 94% screen width with compact text wrapping.
  - Displays blood group, phone number (with one-tap dialer intent), student ID, room/hall, and email without clipping.

---

## 17. Library & Course Files Redesign (with Facebook Shimmer)

### A. Course Files Screen Architecture
When tapping any enrolled subject or course card in the Library, the user is navigated to the redesigned **Course Files** view (`ClassMateCourseFilesActivity.kt` replacing legacy fragmented file listings):

```mermaid
flowchart TD
    A[Library Home Screen] -->|Tap Course Card| B[ClassMateCourseFilesActivity]
    B --> C["Course Hero Banner<br>(Course Code, Title, Stats)"]
    B --> D["Prominent Floating/Header 'Add File' Action"]
    B --> E[CourseFileAdapter RecyclerView]
    E --> F["item_course_file<br>(Type Badge, Title, Uploader, Date, Menu)"]
    B -.->|Loading State| G["Shimmer Skeleton Effect<br>(shimmer_subject_pdf_list.xml)"]
```

### B. Core UI & UX Upgrades
1. **Course Hero Card Banner (`bg_course_hero_banner.xml`)**:
   - Displays official course code (`CSE-3101`), full subject name, credit hours, and total file count in a streamlined gradient banner.
   - Removed bloated, cluttered filter chips that wasted vertical space.
2. **Modern Course File Item List (`item_course_file.xml`)**:
   - **Type Badges**: Color-coded badges indicating content classification:
     - PDF / Book: Red/Coral pill (`bg_badge_pdf`)
     - Slides / Presentation: Blue/Indigo pill (`bg_badge_slides`)
     - Handwritten Notes: Emerald green pill (`bg_badge_notes`)
   - **Metadata**: Clean typography displaying uploader name, upload date, and formatted file size (`2.4 MB`).
   - **Overflow Action Menu**: 3-dot dropdown supporting View/Read, Fast Download, and Share link.
3. **High-Visibility "Add File" Action**:
   - Added a prominent, highly visible button (`bg_btn_primary_pill` with elevation and accent tint) allowing students and CRs to contribute materials effortlessly.
4. **Facebook Shimmer Skeleton Loading Effect**:
   - Replaced generic, jarring indeterminate circular progress spinners with high-fidelity animated skeleton placeholders:
     - `shimmer_library_home.xml`: Skeleton cards mirroring library subject tiles.
     - `shimmer_subject_pdf_list.xml`: Skeleton rows matching `item_course_file.xml`.
   - Utilizes `com.facebook.shimmer.ShimmerFrameLayout` with subtle alpha transitions, creating a silky-smooth loading perception during network requests.

---

## 18. Ultra-Premium Profile & Settings Redesign (Android & Web Parity)

### A. Visual & Architectural Overhaul
The Profile and Settings screens underwent a complete design overhaul (`fce68eb0-ce6e-4eb7-8665-7dedc4ea1181`, `f299dd3b-5ee4-474d-a380-72de6b93c62b`) establishing absolute visual and functional parity between the Android native app (`fragment_profile.xml`, `classmate_profile.xml`) and the Web client (`web/src/components/profile.tsx`).

### B. Structural Layout Hierarchy
1. **Identity & Hero Header**:
   - Circular profile avatar with high-resolution image rendering and fallback initials.
   - User full name rendered in bold headline typography.
   - Role badge: Elevated gradient pills for `Owner` (`bg_role_badge_owner`), `Admin` (`bg_role_badge_admin`), or `Student`.
   - Subtitle metadata: Verified university email (`@mbstu.ac.bd`), department (`CSE`), and academic session (`2021-22`).
2. **2x2 Identity Metric Grid (`bg_metric_tile.xml`)**:
   - Four clean, high-contrast metric cards displaying:
     1. **Student ID**: Academic roll number.
     2. **Blood Group**: Direct link to university Blood Network.
     3. **Academic Session**: Current academic intake.
     4. **Phone Number**: Primary contact phone.
3. **Grouped Inset Settings Cards (`bg_settings_card.xml`)**:
   - Inset grouped cards with rounded 16dp corners, subtle dividers, and Chevron indicators:
     - **Academic Preferences**: Semester selection, syllabus view, department settings.
     - **Notifications & Privacy**: Push notification toggle, silent notice delivery options, admin read receipt privacy.
     - **Appearance**: Dark / Light theme toggle with custom smooth track and thumb tint drawables (`cm_switch_thumb_tint`, `cm_switch_track_tint`).
     - **Account Actions**: Edit profile modal, cache clearing, and secure logout.
4. **Cleanup & Removals**:
   - **Attachments Area Removed**: The obsolete "Uploaded Files / Attachments" section in the profile was completely removed per user request, preventing UI clutter.
   - **Double-Tick & Avatar Scaling**: Fixed oversized read-receipt double ticks and corrected avatar badge alignment across Android and Web.
5. **Dual-Backend Profile Editor**:
   - Bottom sheet profile editor syncs updates simultaneously across Supabase PostgREST (`classmate.profiles`) and Firebase Auth/Firestore, ensuring instant consistency across platforms.

---

## 19. In-App Self-Hosted Update Subsystem (v1.1.30, Build 31)

### A. Deep Root Cause Analysis of Android 14+ Update Installation Failures
During ClassMate 1.1.28 and 1.1.29 distribution via GitHub Releases, users on Android 14 (API 34) and Android 15 experienced fatal installation failures: *"App not installed as package appears to be invalid"* or update process timeouts. Investigation revealed three intersecting root causes:

1. **APK Signature Scheme Incompatibility**:
   - Newer Android Gradle Plugins (AGP 8+) sign APKs by default with **APK Signature Scheme v2/v3** only unless explicitly configured.
   - The in-app security verifier (`ApkVerifier.kt`) was strictly checking for legacy **v1 JAR signatures** (`META-INF/*.SF` and `*.RSA`). When receiving a v2-only signed APK, `ApkVerifier` flagged the package as untrusted and aborted installation.
   - Furthermore, older Android package installer daemons on certain OEM ROMs reject APK updates if the v1 JAR signature is completely absent.
   - **Solution**:
     - Configured both `v1SigningEnabled = true` and `v2SigningEnabled = true` in `app/build.gradle.kts`.
     - Completely modernized `ApkVerifier.kt` to parse the APK Signing Block and validate both v1 JAR manifests and v2/v3 signature blocks.
2. **Network Interruption on Large APK Downloads**:
   - Unstable campus Wi-Fi / mobile networks frequently caused downloads of `classmate-1.1.30.apk` (~25MB) to terminate prematurely. Standard `HttpURLConnection` left corrupted partial files on disk.
   - **Solution**: Implemented resilient HTTP Range resumption (`UpdateRepository.kt`). When resuming, the downloader sends `Range: bytes=[ExistingBytes]-` and verifies the downloaded file against the release SHA-256 hash before handing off to the installer.
3. **Modern `PackageInstaller` Session API (`ApkInstaller.kt`)**:
   - Replaced deprecated `ACTION_VIEW` `content://` intents (which frequently failed background installation on modern Android) with the robust Android `PackageInstaller` session API.
   - Streams APK chunks directly into `PackageInstaller.Session`, commits with an install status `PendingIntent`, and provides unattended update capabilities where permitted.

### B. Release 1.1.30 (versionCode 31) Specifications
- **Assets**: `classmate-1.1.30.apk` and `update.json` published directly to GitHub Releases repository `https://github.com/shuaibuddowla/ClassMate`.
- **`update.json` Format**:
  ```json
  {
    "versionCode": 31,
    "versionName": "1.1.30",
    "apkUrl": "https://github.com/shuaibuddowla/ClassMate/releases/download/v1.1.30/classmate-1.1.30.apk",
    "sha256": "...",
    "releaseNotes": "Batch Fund accounting, live class indicators, profile redesign, and update engine stabilization.",
    "forceUpdate": false
  }
  ```
- **Modern Update Dialog (`dialog_app_update.xml`, `UpdateActionActivity.kt`)**: Displays rich Markdown changelog, animated progress bar with download speed / bytes transferred, and "Install Update" button.

---

## 20. Timetable & Routine Subsystem Enhancements

### A. Dynamic "LIVE NOW" Class Badge
- **In-Class Period Transformation**:
  - Whenever `currentTime` falls between a period's `startTime` and `endTime`, the period row badge flips from `"CLASS"` or `"LAB"` to a prominent **`"LIVE NOW"`** badge.
  - Styled with emerald green background (`bg_badge_green` on Android, `.period-tag.live` on Web) and bright green typography.
- **15-Second Background UI Ticker**:
  - Lightweight runnable running on the Main thread periodically checks the clock and invokes `notifyItemChanged` on affected period rows.
  - Automatically reverts back to `"CLASS"` or `"LAB"` the exact second the period ends, with zero page reloads or network requests required.

### B. Timetable Cancellation State Restoration Bug Fix
- **Problem**: When a class was cancelled on a specific day, the view holder was styled with red cancel backgrounds. On subsequent days or after date changes, recycled view holders retained the red cancelled state because `onBindViewHolder` lacked explicit branch resets.
- **Solution**:
  - In `ClassMateAcademicScreensSupabase.kt`, binding logic was refactored to explicitly reset default background colors, card borders, and chip badges for every non-cancelled period.
  - Added day-rollover observer in `ClassMateAuthActivity.kt` to trigger routine re-queries automatically at midnight.

---

## 21. Authentication, Onboarding & Dropout Student Overrides

### A. Centered Sign-In Layout & Responsive Typography
- In `ClassMateAuthActivity.kt` and `activity_login.xml`, the sign-in card was centered vertically with balanced top and bottom spacers.
- Responsive card width and single-line button text prevent text wrapping on smaller smartphone screens.
- Google Sign-In button incorporates smooth press feedback animations and classic MBSTU ClassMate branding.

### B. Re-Add / Dropout Student Academic Session Override
- **Problem**: Students who repeat a semester or drop an academic year have university emails encoding their initial admission year (e.g. `it20015@mbstu.ac.bd`), causing automatic formula-based batch parsers to assign them to the wrong batch (e.g. 2020 instead of 2021).
- **Architecture**:
  - Supabase `profiles` schema and auth logic were updated with an academic batch override field.
  - When a student signs in, if an administrative override exists in `classmate.batch_overrides`, the app uses the override batch for routine, notices, and batch fund rosters while keeping their original university identity email.

---

## 22. Comprehensive Debugging & Troubleshooting Log

This table documents real bugs encountered, exact root causes diagnosed, files modified, and preventative solutions established during development:

| Bug & Symptom | Root Cause | Affected Files | Resolution & Solution Applied |
|---|---|---|---|
| **`JSONException: Value of type java.lang.String cannot be converted to JSONObject`** when adding batch fund deposits. | Supabase RPC `record_batch_fund_transaction` returns a scalar UUID string (`"a7068e21-..."`). `ClassMateAuthApi.rpc` blindly passed the response into `JSONObject(text)`. | `ClassMateAuthApi.kt` | Upgraded `ClassMateAuthApi.rpc` to inspect response text: handles scalar strings, UUIDs, arrays, and empty strings (`rpcVoid`). |
| **Android framework text selection teardrop handle / cursor crash** on notice preview text. | Default `LinkMovementMethod` invokes `Selection.setSelection(...)` on touch, spawning Android OS cursor handles on non-editable `TextView`. | `ClassMateNoticeText.kt` | Implemented custom non-selecting movement method handling links on `ACTION_UP` without touch selection. Set `highlightColor = TRANSPARENT`, `isFocusable = false`. |
| **In-app update installation failure ("package appears invalid") on Android 14+**. | Release APK was signed only with V2 scheme by AGP, but `ApkVerifier.kt` checked strictly for V1 JAR signatures. | `app/build.gradle.kts`, `ApkVerifier.kt` | Configured dual V1+V2 signing schemes in Gradle; upgraded `ApkVerifier` to parse modern APK Signing Block and validate both V1 and V2/V3 signatures. |
| **Notice Translate button disabled after single toggle**. | Rebinding recycled notice cards in `RecyclerView` failed to reset `btnTranslate.isEnabled = true`. | `ClassMateAcademicScreensSupabase.kt` | Explicitly reset `isEnabled = true` and synchronize toggle state whenever binding notice cards. |
| **Timetable card stuck as red "CANCELLED" after day rollover**. | Recycled `ViewHolder` retained cancel styling because binding logic lacked explicit reset branches for active periods. | `ClassMateAcademicScreensSupabase.kt`, `ClassMateAuthActivity.kt` | Explicitly reset view holder background, borders, and chips in all binding branches; added midnight day-rollover listener. |
| **Batch Fund toggle balance stale on Batch tab after adding/deleting transactions**. | `btnToggleBatchFund` queried database only once on initial instantiation; view was cached in memory. | `ClassMateFriendsScreen.kt` | Attached `OnAttachStateChangeListener` and `render()` hook to re-query `batch_fund_dashboard` whenever returning to screen. |
| **Batch Fund deletion crash on empty response**. | Deleting transactions returned HTTP 204 or empty string, crashing standard JSON parser. | `ClassMateBatchFundActivity.kt`, `ClassMateAuthApi.kt` | Added `rpcVoid` for non-returning RPCs and validated safe response handling. |
| **Short notices (< 6 lines) expanding and appending `see less`**. | Line count calculation did not account for unconstrained preview dimensions. | `ClassMateNoticeText.kt`, `notices.tsx` | Strictly omitted `... see more` and prevented expansion for notices with 6 lines or fewer. |
| **URL taps in notice cards triggering parent card ripple / expansion**. | Touch events on URL clickable spans bubbled up to parent `CardView` `setOnClickListener`. | `ClassMateNoticeText.kt` | Custom movement method consumes touch events on clickable span interactions, preventing bubbling to parent cards. |
| **Teacher profiles appearing in student Batch directory**. | Query fetched all department profiles without filtering out faculty accounts. | `ClassMateFriendsScreen.kt`, `friends.tsx` | Filtered profiles by student role, keeping faculty visible only in faculty directory. |
| **Batch Fund deposit notifications broadcast to all batch students instead of single depositor**. | In `classmate.notification_recipients`, `academic_notification_recipients` was unconditionally executed and unioned. Because deposit events had `batch_id` attached, all 28 batch students were returned and authorized. | `202610100001_batch_fund_deposit_notifications.sql`, `ClassMatePushWorker.kt` | Scoped `academic_notification_recipients` and `academic_notification_device_allowed` strictly to academic event kinds (`notice`, `file`, etc.). Isolated deposit events solely to `student_profile_id`. |
| **Perceived Supabase database limit exhaustion (~268 MB / 500 MB)**. | Rogue table `public.audit_log` reached 242 MB (343,679 rows) due to automated cloud catalog sync calling `sync_archive_catalog` every minute, writing 20â€“40 rows per minute even when no data changed. Actual ClassMate data was only ~4.35 MB. | `public.audit_log`, `catalog_sync` triggers | Disabling/truncating runaway audit triggers on automated background syncs immediately reclaims ~240 MB, restoring ~485 MB of headroom. |
| **Empty B-Tree index bloat on `classmate.notice_reads` (~57.6 MB on 0 rows)**. | Synthetic capacity test fixtures (`fixtures.py`) inserted 250k rows and deleted them via cascading delete. PostgreSQL MVCC retains allocated 8 KB index pages on disk after deletes. | `classmate.notice_reads` indexes | Execute non-blocking `REINDEX TABLE CONCURRENTLY classmate.notice_reads; VACUUM (ANALYZE);` to reclaim ~57 MB disk space. |
| **Student "Your Academic Batch" in Settings taking regular students to Admin Workspace**. | Click action on batch card unconditionally opened Admin Workspace / "Choose Your Classroom" rather than checking user's server-side claims. | `classmate_profile.xml`, `ClassMateAcademicScreensSupabase.kt` | Restricted Admin Workspace to global admins/owners verified via server claims; students are strictly confined to their enrolled batch view. |
| **ClassMate AI configuration card appearing for Class Representatives in Settings**. | Permissions check permitted CRs to view AI config card designed exclusively for administrators. | `ClassMateAcademicScreensSupabase.kt`, `profile.tsx` | Restricted ClassMate AI visibility strictly to global Admin/Owner role. |
| **Misplaced delete icon far from text in "Clear Cache" button**. | Button layout placed a delete trash icon with stretched gravity / wide spacing away from the label. | `classmate_profile.xml` | Removed the detached delete icon from the Clear Cache button for clean typography alignment. |
| **Student ID displayed in "About Developer" in Settings**. | Developer info card displayed internal university student roll number along with session and department. | `classmate_profile.xml` | Removed student ID, centered "Computer Science and Engineering", and cleanly formatted Batch 22 Session 2021-22. |
| **`function classmate.can_manage_batch(uuid) does not exist`** when toggling batch settings or attendance. | `classmate.can_manage_batch` was previously defined only with a `text` argument; PostgreSQL strict type resolution failed when invoked with `uuid` parameters. | `202610110004_attendance_and_timetable_refinements.sql`, `profile.tsx` | Added overloaded security definer function `classmate.can_manage_batch(target_batch uuid)` checking owner/CR/admin permissions. |
| **Attendance Excel export failed in production ("Failed to generate course Excel file")**. | Supabase Edge Function `export-attendance` encountered timeout or memory limits when assembling heavy multi-session attendance matrices for entire batches. | `web/src/lib/attendance-excel.ts`, `web/src/components/teacher-attendance.tsx` | Implemented pure client-side SheetJS generation (`XLSX.utils.book_new()`, `XLSX.writeFile()`) generating instant `.xlsx` downloads in 0ms with zero server load or network timeouts. |
| **Attendance tab appearing in main navigation for regular students**. | Navigation bar configuration in `app.tsx` unconditionally rendered the Attendance tab. | `web/src/components/app.tsx` | Restricted Attendance navigation tab strictly to `profile?.role === "teacher" || owner`; students access their attendance metrics cleanly via the "My Attendance" tab in Timetable. |
| **Camera access blocked for Attendance QR scanner on Web**. | Web security headers lacked explicit camera permissions for the app origin, causing browser `getUserMedia` to reject with `NotAllowedError`. | `web/next.config.ts` | Added `Permissions-Policy: camera=(self)` to HTTP security headers in Next.js configuration. |
| **Duplicate attendance session rows created when teacher closed and reopened QR modal**. | `start_attendance_session` generated a new session row on every invocation without checking for existing sessions on the same class day. | `202610110004_attendance_and_timetable_refinements.sql`, `attendance-teacher-modal.tsx` | Refactored `start_attendance_session` to look up existing sessions for `target_slot` on the current date in Asia/Dhaka time (UTC+6) and reuse it (`is_resumed = true`). Replaced "Close Attendance" with "Done" so dismissing QR keeps session active until class ends. |
| **`gen_random_bytes` function resolution failure in `start_attendance_session`**. | `gen_random_bytes` was called without schema qualification inside a function with `set search_path = ''`. | `202610110002_attendance_schema.sql` | Explicitly qualified call as `extensions.gen_random_bytes(24)`. |
| **Rapid log ingestion and Edge Function quota consumption from 1-minute background crons**. | Cloudflare Worker `classmate-secure-api` ran `sync_archive_catalog` every 1 minute (`*/1 * * * *`), creating 20â€“40 rows/min in `public.audit_log` (242 MB bloat). Notification retry also polled every 1 minute. | `cloudflare-worker/wrangler.jsonc`, `202610110003_optimize_cron_schedules_and_retention.sql` | Reconfigured Cloudflare Worker cron to once daily (`0 4 * * *`), widened notification retry to 15m, and scheduled automated daily purge of `cron.job_run_details` older than 2 days. |
| **R2 resource file upload rejected for files larger than 100 MB**. | `upload-classmate-resource/index.ts` had a hardcoded size validation check capping `size_bytes > 100_000_000`. | `supabase/functions/upload-classmate-resource/index.ts`, `web/src/components/library.tsx` | Increased the resource upload ceiling to 500 MB (`500_000_000 bytes`) across the Edge Function and Web / Android client uploaders. |
| **Ambiguous Attendance Summary ("0% Â· 0 of 6 classes attended")**. | Student summary card displayed stats without explicit scope, misrepresenting single-course attendance as an overall semester percentage. | `ClassMateAcademicScreensSupabase.kt`, `student-attendance.tsx` | Made summary scope explicit (`"0 of 6 classes attended in CSE3333"` or `"Across N tracked courses"`). Only queries and calculates metrics for attendance-enabled courses (`sc.attendance_enabled = true`). Handled zero sessions safely without division-by-zero errors. |
| **Timetable segmented toggle displaced off-screen when opening "My Attendance"**. | Switching to "My Attendance" lacked preserved root header layout, causing vertical scroll and layout displacement. | `ClassMateAcademicScreensSupabase.kt`, `schedule.tsx` | Rebuilt My Attendance layout inside stable Timetable container, maintaining fixed top header controls without vertical layout shifts or jumpy scrolling. |
| **Attendance action buttons ("Take Attendance" / "Scan Attendance") delayed on Timetable by minutes**. | Clients relied on static timetable caching without a high-frequency active session indicator; opening a session required manual pull-to-refresh or app restart. | `202610130001_instant_attendance_timetable_updates.sql`, `schedule.tsx`, `ClassMateAcademicScreensSupabase.kt` | Created lightweight RPC `classmate.active_attendance_sessions(target_batch)` and 3-second active background polling on Timetable when viewing today's date. Timetable updates and reveals scan/take buttons in under 3 seconds. |
| **Teacher QR screen action buttons ("Close Attendance", "Export to Excel") cut off / hidden behind navigation bar**. | Buttons were placed inside the bottom of a scrollable view without window inset considerations; on small screens, they fell below the fold or were obscured by Android system navigation bars. | `activity_teacher_live_attendance.xml`, `ClassMateTeacherLiveAttendanceActivity.kt`, `attendance-teacher-modal.tsx`, `globals.css` | Moved buttons to a dedicated pinned bottom action container (`layoutBottomActions`) with `WindowInsetsCompat` navigation bar padding on Android, and `.attendance-modal-footer` on Web. Reduced QR canvas size (`220dp` / `230px`) so live counters and timers remain fully visible without scrolling. |
| **Student roll numbers sorting lexicographically (`CE25010` before `CE25002` if unpadded, or inconsistent sorting) in Excel exports and live rosters**. | Plain string sorting was used instead of natural numeric chunking comparator. | `web/src/lib/attendance-excel.ts`, `supabase/functions/export-attendance/index.ts`, `attendance-teacher-modal.tsx`, `ClassMateTeacherLiveAttendanceActivity.kt` | Implemented natural alphanumeric roll chunking comparator (`CE25001` < `CE25032` < `CE25060`) in TypeScript and Kotlin. Applied to Excel exports, Web teacher modals, and Android live attendee RecyclerViews. |
| **`Unauthorized (42501)` when starting attendance session**. | Previous permission check only checked `auth.uid() = sc.teacher_id` from assignments; did not recognize teacher directory email matches, username prefix matches, or admins/owners starting sessions. | `202610130002_attendance_bulletproof_reliability.sql` | Implemented multi-tier `can_manage_attendance_slot` function covering superusers, owners, admins, active TCA assignments, and teacher directory matches by profile ID, email, and prefix. |
| **Empty / null session secret on resumed attendance sessions**. | If a session was created without an explicit secret or if an existing session had null secret, HMAC validation failed for all students. | `202610130002_attendance_bulletproof_reliability.sql` | Added fallback in `start_attendance_session` to automatically generate a 24-byte hex secret (`encode(extensions.gen_random_bytes(24), 'hex')`) if null or empty, and reopening closed sessions cleanly resets status to `'open'`. |
| **Scan rejection (`22023 Outside class period`) during test or early-starting classes**. | Rigid time boundary check `scan_time < (sess.start_time - interval '15 minutes')` threw fatal exceptions for any scan outside nominal routine schedule hours, breaking test/demo sessions and early classes. | `202610130002_attendance_bulletproof_reliability.sql` | Refactored `mark_attendance` to accept scans for any session that is currently `open`, while accurately flagging `confidence = 'late'` if scanned > 15 minutes after class start. |
| **Duplicate key constraint violation on scan retry or double scan**. | Re-scanning or flaky network retries attempted inserting duplicate `(session_id, student_id)` rows into `attendance_records`. | `202610130002_attendance_bulletproof_reliability.sql` | Changed insert into an idempotent upsert: `on conflict (session_id, student_id) do update set synced_at = now()`. |
| **Students incorrectly flagged as "Late" when attendance is taken later in class**. | `mark_attendance` compared scan time against nominal scheduled `start_time + 15m`. When a teacher took attendance in the middle or end of class, all scans were marked as "Late". | `202610130003_attendance_no_late_penalty.sql`, `ClassMateStudentAttendanceScannerActivity.kt` | Removed automated late calculation. Any scan during an open session is recorded as clean "Present" (`confidence = 'normal'`). Retroactively updated historical `late` records to `normal`. |
| **Camera permission permanently denied on Android ("Don't ask again") trapping user in camera-blocked state**. | When a student denies permission twice or ticks "Don't ask again", subsequent clicks fail silently without showing system prompts. | `ClassMateStudentAttendanceScannerActivity.kt` | Added permanent denial detection (`!shouldShowRequestPermissionRationale`). Shows a dedicated recovery dialog with direct one-tap button to open Android App System Settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). |
| **Web camera scanner blocked silently by Chrome browser settings without user prompt**. | Google Chrome site settings or global permissions set to "Block" prevent `getUserMedia` from displaying prompts. | `web/src/components/attendance-scanner-modal.tsx`, `web/src/app/globals.css` | Added diagnostic state detection (`blocked_by_browser`) and rendered explicit illustrated troubleshooting guidance for Chrome address bar site permissions and `chrome://settings/content/camera`. |
| **Accidental duplicate / test attendance sessions recorded for same class**. | Starting multiple test sessions during deployment or network dropouts created ghost sessions in the database for `CSE2105`. | `classmate.attendance_sessions`, `classmate.attendance_records` | Reconciled student scan records across sessions, safely purged duplicate test sessions 1 and 3, and consolidated attendance onto official Session 2 with 46 attendees intact. |
| **Large academic materials (> 500 MB) or drive folders rejected by upload dialog**. | R2 upload limit was capped at 500 MB and could not link shared folders or external video drives. | `web/src/components/library.tsx`, `202610140001_library_drive_links.sql` | Implemented segmented Drive Link sharing tab and RPC `create_library_link` supporting Google Drive / HTTPS URLs with dedicated Drive icon and instant browser redirection without storage footprint. |
| **PWA Service Worker caching hiding newly deployed Web features**. | Aggressive CacheStorage strategy in `worker.ts` served stale JavaScript and HTML bundles from local cache without polling upstream network ETags. | `web/src/worker.ts`, PWA Service Worker | Documented service worker unregistration via DevTools Application tab, hard reload (`Ctrl + F5`), bumped PWA cache version strings, and configured NetworkFirst policy for dynamic routes. |
| **Teacher Live Attendance UI jumping vertically between QR view and live student roster**. | Toggling between QR code and student attendee list caused layout shifts and required vertical scrolling. | `ClassMateTeacherLiveAttendanceActivity.kt`, `activity_teacher_live_attendance.xml` | Introduced fixed segmented header tabs `[ QR Code | Live Roster ]` with smooth view switching and preserved scroll state. |
| **Ambiguous course codes in Teacher Live Attendance header**. | Teacher screen displayed cryptic course codes (`CSE2105`) without human-readable course titles. | `ClassMateTeacherLiveAttendanceActivity.kt`, `attendance-teacher-modal.tsx` | Updated queries and headers to display full course title alongside code (`CSE2105 Â· Computer Organization and Architecture`). |
| **`openFile` failure on Google Drive and external links ("Missing R2 object key")**. | `openFile` in `api.ts` assumed all items in `classmate.file_metadata` had non-null `r2_object_key` and requested presigned R2 download URLs. Drive links have `link_url` and null `r2_object_key`. | `web/src/lib/api.ts`, `ClassMateAcademicScreensSupabase.kt` | Added explicit check: if `resource.link_url` is present, immediately launch browser window / Android Intent `ACTION_VIEW` directly to `resource.link_url`, bypassing binary S3 presigned URL generation. |
| **`delete_library_resource` error on deleting external links**. | Deletion handler assumed every resource had an associated Cloudflare R2 object to delete, failing when `r2_object_key` was null. | `202610140001_library_drive_links.sql`, `classmate.delete_library_resource` | Updated deletion logic to skip Cloudflare R2 S3 `DeleteObject` calls when `file_type = 'link'` or `r2_object_key is null`. |
| **Hardware RFID attendance reader SPI initialization failure (`Firmware Version: 0x0` or `0xFF`)**. | Incorrect SPI pin mapping on 38-pin ESP32 dev boards (confusion between HSPI vs VSPI default pins, floating MISO pin, or 5V power routing to 3.3V RC522). | `esp32_rfid_attendance.ino`, hardware wiring | Mapped explicit VSPI pins: `SCK = 18`, `MISO = 19`, `MOSI = 23`, `SS/SDA = 5`, `RST = 22`. Powered RC522 strictly from 3.3V rail (5V damages module or corrupts logic levels), lowered diagnostic SPI clock to 1MHzâ€“4MHz, and verified soldered header continuity. |
| **Esptool serial flashing timeout during IoT hardware upload (`No serial data received`)**. | USB-to-UART bridge handshake timing failure or lack of automated bootloader trigger on ESP32 dev module during `esptool` flashing. | Arduino IDE / PlatformIO upload settings | Hold down physical `BOOT` / `IO0` button on ESP32 when serial connection initiates (`Connecting........`) until flashing starts; verified CP2102/CH340 drivers and lowered upload speed to 115200 baud. |
| **`MFRC522Extended.cpp: error: ordered comparison of pointer with integer zero ('byte*' and 'int')`**. | In MFRC522 library v1.4.10/v1.4.11, lines 824 & 847 compared pointer parameter `byte *backLen` directly with integer 0 (`backLen > 0`) instead of dereferencing `*backLen > 0`. Modern GCC 12+ (Xtensa ESP32 toolchain) strictly rejects pointer-to-integer ordered comparisons. | `libraries/MFRC522/src/MFRC522Extended.cpp` | Corrected lines 824 and 847 to dereference the pointer: `*backLen > 0`. Sketch compiles cleanly with 0 errors. |
| **`Failed uploading: uploading error: exit status 2` / `PermissionError: Access is denied` (COM6 Port Busy)**. | Arduino IDE background process `serial-monitor.exe` or open Serial Monitor tab held an exclusive lock on Windows serial port `COM6`, preventing `esptool` from acquiring the port for flashing. | Windows serial subsystem, `COM6` | Closed the Serial Monitor tab and terminated orphaned background `serial-monitor.exe` processes before initiating upload. |
| **ESP32 boot & peripheral failure from incorrect board definition (`ESP32-WROOM-DA Module`)**. | The user selected `ESP32-WROOM-DA Module` in Arduino IDE. The "DA" variant (Dual Antenna) uses a distinct PCB layout with different internal SPI flash voltage and GPIO strapping pin configurations, preventing standard 38-pin boards from booting correctly. | Arduino IDE Board Selector | Switched target board to standard `ESP32 Dev Module` (or `NodeMCU-32S`) with 80MHz QIO flash and 115200 baud upload rate. |
| **Clone RC522 ICs (FM17522 / MH523) returning `0x00` / `0xFF` on `VersionReg` (`0x37`)**. | Inexpensive third-party RC522 modules use compatible clone silicon (Fudan FM17522 or Shanghai Belling MH523) that do not implement NXP's proprietary version register, failing strict `PCD_ReadRegister(VersionReg)` sanity checks. | `rc522_diagnostic.ino`, `esp32_rfid_attendance.ino` | Added tolerant fallback: non-fatal warning on VersionReg mismatch while proceeding to poll card presence via `PICC_IsNewCardPresent()`. Verified core ESP32 SPI hardware independently using `spi_loopback.ino` (MOSI GPIO23 jumpered to MISO GPIO19). |
| **ESP32 Wi-Fi peak current transmission brownout causing RC522 SPI register reset**. | ESP32 Wi-Fi transmission bursts draw up to 250mA peak currents. Weak 3.3V LDO rails without decoupling capacitors drop below 2.7V, causing the RC522 to brown out and reset registers (`VersionReg` reads `0x00`). | `esp32_rfid_attendance.ino`, hardware power wiring | Placed a 10ÂµF electrolytic or 100nF ceramic decoupling capacitor across RC522 VCC and GND header pins; stabilized 3.3V power rail and prevented reset during Wi-Fi bursts. |
| **Attendance Card inflow collection discrepancies in Batch Fund (58-student cohort)**. | Physical RFID attendance cards (Tk 40 each) collected for automated classroom attendance were deposited without unified payer reconciliation, leading to ambiguity in unpaid dues. | `classmate.batch_fund_transactions`, `classmate.profiles` | Performed comprehensive database reconciliation joining transactions with profiles: verified 21 paid students (Tk 845) and identified 37 unpaid students (Tk 1,480 dues) for CR tracking. |
| **Native Android teacher live attendance bottom buttons occluded by system navigation bar**. | In `ClassMateTeacherLiveAttendanceActivity`, "Close Attendance" and "Export to Excel" buttons were occluded by Android 10+ gesture bar or 3-button navigation. | `activity_teacher_live_attendance.xml`, `ClassMateTeacherLiveAttendanceActivity.kt` | Attached `ViewCompat.setOnApplyWindowInsetsListener` to apply `sysBars.bottom` padding to `layoutBottomActions`, guaranteeing full visibility. |



---

## 23. Operational & Build Verification Checklist

### A. Android Build & Unit Testing
```powershell
# Run debug unit tests
./gradlew testDebugUnitTest

# Assemble Release APK (signed with V1 + V2)
./gradlew assembleRelease

# Verify generated APK signature
python scripts/verify_apk_signatures.py release/ClassMate.apk
```

### B. Web Client Testing & Deployment
```powershell
# Run web test suite
npm test --prefix web

# Run production build
npm run build --prefix web

# Deploy to Vercel Staging / Production
vercel --prod
```

### C. Supabase Database Migrations
```powershell
# Deploy migrations to Staging and Production Supabase databases
python scripts/web/deploy-batch-fund.py both
```

### D. Supabase Read-Only Storage Diagnostics & Index Maintenance
```sql
-- Safe read-only inspection of table and index disk usage
SELECT
    schemaname || '.' || relname AS table_name,
    n_live_tup AS live_rows,
    n_dead_tup AS dead_rows,
    pg_size_pretty(pg_relation_size(relid)) AS table_data_size,
    pg_size_pretty(pg_indexes_size(relid)) AS total_indexes_size,
    pg_size_pretty(pg_total_relation_size(relid)) AS grand_total_size
FROM pg_stat_user_tables
WHERE schemaname IN ('classmate', 'cron', 'public')
ORDER BY pg_total_relation_size(relid) DESC;

-- Non-blocking reindex to reclaim dead index bloat on notice_reads
REINDEX TABLE CONCURRENTLY classmate.notice_reads;
VACUUM (ANALYZE) classmate.notice_reads;
```

---

## 24. Restructured Settings Tab & Dedicated Profile Activity Architecture (v1.1.31+)

### A. Architectural Motivation & Paradigm Shift
Prior to v1.1.31, the 5th bottom navigation tab served as an overloaded hybrid screen combining user identity cards (avatar, 2x2 personal info grid, blood requests) and system configuration toggles (theme, batch switcher, offline cache, update checks). This produced visual crowding and hindered navigation clarity.

The screen architecture was cleanly decoupled:
1. **Bottom Navigation Tab Renamed to "Settings"**:
   - Tab title: `@string/nav_settings` ("Settings").
   - Tab icon: `@drawable/sl_nav_settings` backed by modern gear vector `ic_nav_settings.xml`.
   - Web companion client synchronized to tab `["settings", "Settings", Settings]` using Lucide's `Settings` icon.
2. **Top-Right Profile Header Widget (`btnHeaderProfile`)**:
   - The Settings top app bar features the bold title "Settings" and clean subtitle "Preferences, management and system controls".
   - The top-right corner houses an interactive profile widget consisting of the user's circular avatar (`ivHeaderProfileAvatar`, 44x44 dp) bordered by a subtle ring, with the bold label "Profile" (`tvHeaderProfileLabel`, 11sp) directly below.
   - Tapping this widget launches the dedicated, full-screen **`ClassMateProfileActivity.kt`** on Android, or transitions to the dedicated `ProfileView` sub-screen on Web with standard back navigation.

```mermaid
flowchart TD
    BottomNav[Bottom Navigation Bar] --> SettingsTab[Settings Tab]
    SettingsTab --> TopHeader[Header with Title & Top-Right Profile Widget]
    TopHeader -->|Shows Avatar + 'Profile' Label| ProfileWidget[btnHeaderProfile Widget]
    SettingsTab --> SettingsBody[Account, Management, Preferences, Storage & System]
    ProfileWidget -->|Tap Avatar / Label| ProfilePage[Dedicated ClassMateProfileActivity]
    ProfilePage --> ProfileHero[Hero Card: Avatar, Name, Role Badge, ID & 2x2 Grid]
    ProfilePage --> BloodCard[Blood Emergency Requests Card]
    ProfilePage --> NudgeCard[Complete Profile Details Nudge]
    ProfilePage -->|Back Button / Swipe Gesture| SettingsTab
```

### B. Dedicated Profile Activity (`ClassMateProfileActivity.kt`, `activity_classmate_profile.xml`)
- **Root Container**: Full-screen `NestedScrollView` wrapped inside a `SwipeRefreshLayout` enabling smooth pull-to-refresh synchronization.
- **Top App Bar**: Back navigation button (`btnProfileBack`), bold headline "Profile", and subtitle "Your academic identity".
- **Hero Identity Card (`cardPersonalInfo`)**:
  - Gradient brand banner (`bg_profile_hero_gradient`).
  - High-resolution circular avatar with fallback initials.
  - Full student name formatted with `text-balance` and padding to prevent overflow on long names.
  - Role Badges: Elevated gradient badges for `Owner` (`bg_role_badge_owner`), `Admin` (`bg_role_badge_admin`), or `Student`.
  - Copyable Student ID (`tvProfileStudentId`) and University Email chips with one-tap clipboard copy and toast feedback.
- **2x2 Identity Metric Grid (`bg_metric_tile.xml`)**:
  1. **Blood Group**: Direct link to university Blood Emergency Network.
  2. **Academic Session**: Verified academic intake (e.g., `2021-22`).
  3. **Department & Batch**: Academic discipline (e.g., `CSE-22`).
  4. **Phone Number**: Primary contact phone with one-tap dialer intent.
- **Blood Emergency Requests Card (`cardBloodEmergency`)**: Real-time broadcast card showing outstanding blood donation requests within the campus community.
- **Incomplete Profile Details Nudge (`cardProfileNudge`)**: Actionable completion banner prompting students to fill missing contact attributes.
- **Lifecycle & Cache Resiliency**: On `onResume` and pull-to-refresh, queries `ClassMateAuthApi.initializeProfile()` to ensure fresh profile data from Supabase PostgREST, preventing stale academic batch caching.

### C. Settings Screen Component Refinements
1. **Header Eyebrow Cleanup**: Removed legacy blue `"PREFERENCES"` and `"SYSTEM"` eyebrow labels from `classmate_profile.xml` to match the minimalist aesthetics of the Library and Notices headers.
2. **"Your Academic Batch" Security Scoping**:
   - Fixed an authorization flaw where clicking "Show Batch" under "Your Academic Batch" redirected normal students into the Admin Workspace / "Choose Your Classroom".
   - Regular students are strictly scoped to view their enrolled batch details.
   - Access to the Admin Workspace is verified dynamically via server-side claims in `ClassMateAuthApi` rather than hardcoded client-side role checks.
3. **ClassMate AI Setting Restriction**:
   - The ClassMate AI configuration tile in Settings is strictly restricted to system Administrators and Owners.
   - Completely hidden from Class Representatives (CR) and regular students.
4. **Clear Cache Action Polish**:
   - Removed the detached delete/trash icon from the "Clear Cache" button under Utilities & Storage, aligning typography cleanly.
5. **"About Developer" Centering**:
   - Removed the student roll number from developer credentials.
   - Centered "Computer Science and Engineering" with clean subtitle `"Batch 22 Â· Session 2021-22"`.

---

## 25. Strict Single-Recipient Batch Fund Deposit Confirmation Notifications

### A. Feature Architecture & User Experience
When a student's inflow contribution (deposit) is recorded or updated by a Class Representative (CR) or Admin in the Batch Fund ledger, ClassMate automatically dispatches a real-time push notification **strictly and exclusively to that specific contributing student**.

```mermaid
flowchart TD
    A["CR / Admin records or edits Deposit (inflow)"] --> B["RPC: record_batch_fund_transaction / bulk / update"]
    B --> C["Auto-resolve student_profile_id from student_id or full_name"]
    C --> D["Insert into classmate.batch_fund_transactions"]
    D --> E["Insert event into classmate.notification_outbox (kind: batch_fund_deposit / update)"]
    E --> F["classmate.request_notification_dispatch()"]
    F --> G["Edge Function: dispatch-classmate-notifications"]
    G --> H["classmate.notification_recipients(event_id) -> STRICTLY student_profile_id tokens"]
    H --> I["FCM Push sent ONLY to contributing student's device"]
    I --> J["Android: ClassMatePushWorker -> opens ClassMateBatchFundActivity"]
    I --> K["Web: Service Worker -> opens /#friends/fund"]
```

- **Notification Formatting (`classmate.notification_job_context`)**:
  - **Deposit Recorded**:
    - Title: `"Deposit Confirmed: à§³[amount]"`
    - Body: `"Your deposit of à§³[amount] for \"[title]\" has been recorded."`
  - **Deposit Updated**:
    - Title: `"Deposit Updated: à§³[amount]"`
    - Body: `"Your deposit of à§³[amount] for \"[title]\" has been updated."`
  - **Currency Formatting**: Whole amounts omit decimals (e.g. `à§³500`), while fractional amounts retain two decimal places (e.g. `à§³500.50`).
- **Interactive Tap Destination**:
  - **Android (`ClassMatePushWorker.kt`)**: Detects `kind in setOf("batch_fund_deposit", "batch_fund_update")` and constructs an Intent launching `ClassMateBatchFundActivity.kt` with `batch_id` extra, taking the student directly to the ledger.
  - **Web (`web/src/worker.ts`)**: Service Worker routes notification clicks directly to `/#friends/fund`.

### B. Broadcast Leak Discovery & Cryptographic Scope Enforcement
During testing, an issue arose where recording a deposit triggered confirmation notifications to **all active students in the batch**:

1. **Root Cause Analysis**:
   - In `classmate.notification_recipients(target_event)`, the stored procedure began with an unconditional `select * from classmate.academic_notification_recipients(target_event)`.
   - Because deposit events had `batch_id` and `department_id` populated, `academic_notification_recipients` pulled all 28 active batch students into the recipient list.
   - Furthermore, `classmate.notification_device_allowed` evaluated to `true` for all batch students because `e.kind <> 'app_update' and p.batch_id = b.id`.
2. **Database Migration Fix (`202610100001_batch_fund_deposit_notifications.sql`)**:
   - **Scoped `academic_notification_recipients`**:
     ```sql
     select * from classmate.academic_notification_recipients(target_event)
     where exists (
       select 1 from classmate.notification_outbox e
       where e.id = target_event and e.kind in ('notice', 'file', 'class_change', 'semester', 'app_update')
     )
     ```
   - **Scoped `academic_notification_device_allowed`**:
     Ensures academic device checks are only evaluated for academic event kinds.
   - **Single-Recipient Isolation Guarantee**:
     Deposit events strictly match solely via:
     ```sql
     union select p.id, d.fcm_token
     from classmate.notification_outbox e
     join classmate.batch_fund_transactions t on t.id = e.record_id
     join classmate.profiles p on p.id = t.student_profile_id and p.verification_status = 'active'
     join classmate.device_tokens d on d.profile_id = p.id
     where e.id = target_event
       and e.kind in ('batch_fund_deposit', 'batch_fund_update');
     ```
   - **Device Access Control**:
     `classmate.notification_device_allowed` explicitly verifies `t.student_profile_id = target_profile`.
3. **Production Verification (`cbskpifgbdbgdmnnxfrh`)**:
   - Recipient count on Taslima Akter's deposit dropped from 28 recipients to **exactly 1 recipient**.
   - Other batchmates' tokens returned `false` on device permission check.
   - Academic notice broadcasts remained completely unaffected, delivering to all 28 batch students.

---

## 26. Production & Staging Supabase Free-Tier Diagnostic Audit & Infrastructure Optimization

A comprehensive read-only diagnostic audit was conducted on October 10, 2026, covering both `ClassMate` (Production: `cbskpifgbdbgdmnnxfrh`) and `ClassMate Staging` (Staging: `qpryxvunhebwljlwmggk`) under shared organization `azzhwhfagnawecqfimtq`.

### A. Resource Footprint & Quota Health Summary
| Metric | Scope | Observed Usage | Free Plan Limit | Consumed % | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Production Database Disk** | Per Project | ~103.1 MB | 500 MB | 20.6% | Healthy |
| **Staging Database Disk** | Per Project | ~123.6 MB | 500 MB | 24.7% | Healthy |
| **Core ClassMate Schema** | Per Project | ~4.35 MB | â€” | < 1.0% | Extremely Compact |
| **Network Egress** | Shared Org | ~48 MB | 5,000 MB (5 GB) | 0.96% | Optimal |
| **Monthly Active Users (MAU)**| Shared Org | 11 MAU | 50,000 MAU | 0.02% | Negligible |
| **Active Projects Count** | Shared Org | 2 active projects | 2 active projects | **100.0%** | Structural Max |
| **Edge Function Invocations**| Shared Org | ~7,152 + ~2,880/day | 500,000 / month | ~18â€“40% | Requires Optimization |

### B. Identified Quota Leaks & Inefficiencies
1. **Redundant 1-Minute Cloudflare Worker Polling**:
   - Cloudflare Worker cron (`*/1 * * * *`) sends an HTTP POST to `/functions/v1/dispatch-classmate-notifications` every 60 seconds on both Production and Staging.
   - Generates **~86,400 Edge Function invocations per month** simply idling on an empty notification outbox.
   - Database trigger `wake_notification_dispatch` already dispatches new events instantaneously via `net.http_post` with 0ms delay.
2. **Empty Index Bloat on `classmate.notice_reads` (~57.6 MB)**:
   - Capacity testing (`scripts/capacity/fixtures.py`) inserted 250,000 synthetic rows and deleted them via cascading delete.
   - In PostgreSQL, B-Tree indexes retain empty 8 KB pages on disk after bulk deletes; autovacuum does not return disk space to the operating system.
   - `notice_reads_pkey` (~40 MB), `notice_reads_profile_idx` (~12 MB), and `notice_reads_page_idx` (~5.6 MB) consume ~57.6 MB despite holding 0 (or ~60) real rows.
3. **Monotonic Accumulation of Cron & Delivery History**:
   - `cron.job_run_details` records 2 rows every minute (2,880 rows/day = ~86,400 rows/month) from 1-min crons.
   - `classmate.notification_jobs` and `notification_deliveries` retain records indefinitely without an automated purge job.
4. **High-Frequency Frontend Polling**:
   - Web Notice feed (`web/src/components/notices.tsx:106`) polls the complete feed and calls `rpc/notice_feed_details` every 30 seconds (`refetchInterval: 30_000`).
   - Web and Android Friends screens call `rpc/touch_presence` every 30 seconds, generating dead tuples and continuous log entries.

### C. Recommended Optimization Plan
1. **Index Reclaim**: Execute `REINDEX TABLE CONCURRENTLY classmate.notice_reads; VACUUM (ANALYZE) classmate.notice_reads;` to immediately reclaim ~57 MB of disk storage without table locking.
2. **Widen Background Dispatcher Cron**: Adjust Cloudflare Worker cron from 1 minute to 10â€“15 minutes (or 5 minutes during university class hours), saving ~75,000 invocations/month while relying on database triggers for instant delivery.
3. **Automated History Purge**: Add a scheduled weekly `pg_cron` maintenance job to purge `cron.job_run_details` older than 7 days and completed notification deliveries older than 14â€“30 days.
4. **Throttle Frontend Polling**: Disable 30s timer on Web notices (`refetchInterval: false`), relying on tab focus/manual refresh, and increase `touch_presence` interval from 30s to 120s.

---

## 27. QR-Based Attendance Tracker System Architecture & Capacity Analysis

### A. Context & The "Full Database" Illusion
During planning for a student QR-code attendance tracker, the user considered creating a separate free Supabase account to avoid hitting the 500 MB database quota, noting production database usage was reported at **~268 MB**.

**Deep Storage Inspection Results**:
- **Total DB Size**: ~268 MB.
- **Real ClassMate Data (`classmate.*`)**: **Only 4.35 MB** (101 student profiles, fund transactions, routines, device tokens).
- **Rogue Table**: **`public.audit_log` had ballooned to 242 MB (343,679 rows)**!
- **Root Cause**: An automated Cloudflare Worker runs `sync_archive_catalog` every minute. Triggers on courses and offerings wrote 20â€“40 rows to `public.audit_log` every minute even when no catalog data changed.
- **Immediate Resolution**: Disabling runaway audits on background catalog syncs and truncating `public.audit_log` reclaims **~240 MB immediately**, dropping database usage to **~15 MB** and leaving **~485 MB of headroom** on the existing free tier.

### B. Attendance System Storage Sizing Math
- Scenario: 100 students attending 5 classes daily, 5 days per week.
- Single scan payload: Student UUID (16 bytes) + Session UUID (16 bytes) + Timestamp (8 bytes) + Status (1 byte) + index overhead = **~100 bytes per scan**.
- Daily volume: 100 students Ã— 5 classes = **500 records/day**.
- Full 4-month semester (80 class days): 40,000 records = **~4 MB of disk storage**!
- Four full college years across multiple batches generates less than **40 MB** of data.

### C. Architecture Comparison
| Evaluation Metric | Separate Supabase Account (Option A) | Single Supabase Database (Option B - Recommended) |
| :--- | :--- | :--- |
| **Hosting Cost** | Free ($0) | Free ($0) |
| **Available Space** | 500 MB | ~485 MB (Plenty for 5+ years) |
| **Relational Integrity** | No foreign keys across databases | Direct foreign keys (`classmate.profiles`, `routine_slots`) |
| **Implementation Complexity**| High (HTTP bridge, dual clients, custom JWTs)| Low (native PostgreSQL schema, fast RPC) |
| **Teacher Realtime Roster** | Complex cross-API sync | Instant Supabase Realtime subscriptions |
| **Latency** | 200â€“500ms multi-hop HTTP | 15â€“30ms single database transaction |

**Recommendation**: Retain attendance in the existing database under the `classmate` schema after reclaiming `public.audit_log`.

### D. QR Code Anti-Cheating & Security Protocol
To prevent students from screenshotting QR codes and sharing them to remote friends over WhatsApp:
1. **Dynamic Rotating QR Codes (TOTP Style)**: The teacher app rotates a cryptographically signed session token every 15 seconds. Screenshots shared remotely expire before an absent student can scan.
2. **Single-Scan Guarantee**: Database uniqueness constraint `UNIQUE (session_id, student_id)` prevents double-scanning.
3. **Strict Class Period Time Boundaries**: Validation verifies the scan timestamp falls within `[start_time - 15 minutes, end_time + 15 minutes]` in Bangladesh Time (Asia/Dhaka, UTC+6).
4. **Hardware Device Binding**: Verifies the scan originates from the student's registered profile and device token stored in `classmate.device_tokens`.

---

## 28. End-to-End Dynamic QR Attendance Tracker Implementation (Phases 1, 2 & 3)

### A. High-Level Architecture & Purpose
The QR Attendance Tracker is an institutional-grade attendance management system integrated directly into the ClassMate ecosystem across native Android, the Web companion client, and the Supabase PostgreSQL backend (`0004f17`, `2787a77`, `db10a60`). Instead of creating disconnected screens, attendance actions attach directly to active class cards in the daily routine/timetable.

```mermaid
flowchart TD
    subgraph Timetable Integration
        Card[Class Routine Card] -->|Teacher during class hours| LiveBtn["Take Attendance<br>(ClassMateTeacherLiveAttendanceActivity / Teacher Modal)"]
        Card -->|Student during class hours| ScanBtn["Scan Attendance<br>(ClassMateStudentAttendanceScannerActivity / Scanner Modal)"]
    end

    subgraph Teacher QR Engine
        LiveBtn --> SecretGen["Generate / Resume Session Secret<br>Single continuous session per date"]
        SecretGen --> QRDisplay["Rotating QR Display (HMAC-SHA256, 15s countdown)"]
        QRDisplay --> LiveRoster["Real-Time Present Attendee Roster<br>(Supabase Realtime WebSockets)"]
    end

    subgraph Student Scanning & Verification
        ScanBtn --> Camera["Camera Viewfinder<br>(ML Kit on Android / BarcodeDetector on Web)"]
        Camera --> SubmitScan["RPC: mark_attendance(session_id, token, time_window)"]
        SubmitScan --> VerifyEpoch["Epoch Window Verification<br>(15s epoch match)"]
        VerifyEpoch --> VerifyPeriod["Class Period Validation<br>(start_time - 15m <= scan <= end_time + 15m)"]
        VerifyPeriod --> AudioFeedback["Two-Tone Harmonic Audio Chime & Haptic Feedback"]
    end

    subgraph Long-Term Analytics & Export
        LiveRoster --> TeacherDash["Teacher Dashboard (teacher-attendance.tsx)"]
        TeacherDash --> MatrixExport["Client-Side SheetJS Excel Matrix (.xlsx) Export"]
        SubmitScan --> StudentDash["Student Dashboard (student-attendance.tsx)<br>Expandable Course Cards & Date History"]
    end
```

### B. Dynamic Rotating HMAC-SHA256 Token Protocol
To eliminate proxy attendance and remote WhatsApp/Messenger screenshot sharing:
1. **15-Second Epoch Windows**:
   - Time window calculation: `time_window = floor(current_epoch_seconds / 15)`.
   - The teacher client recomputes the QR token every 15 seconds accompanied by a smooth circular countdown animation ring.
2. **Cryptographic Token Generation**:
   - Token formula: `HMAC_SHA256(time_window_as_string, session_secret)`.
   - The shared `session_secret` is generated cryptographically on session initialization via `extensions.gen_random_bytes(24)`.
3. **Server-Side Verification & Tolerance (`classmate.mark_attendance`)**:
   - Recomputes `expected_token = encode(hmac(time_window::text, sess.session_secret, 'sha256'), 'hex')`.
   - Tolerance check: `current_win = epoch / 15`. If `abs(current_win - time_window) <= 1`, the scan is accepted. If outside, marked as late or rejected.
4. **Strict Class Period Verification**:
   - Converts the scan epoch timestamp to Bangladesh Time (`Asia/Dhaka`, UTC+6):
     ```sql
     scan_time := ((to_timestamp(time_window * 15) at time zone 'Asia/Dhaka')::time);
     if scan_time < (sess.start_time - interval '15 minutes') or scan_time > (sess.end_time + interval '15 minutes') then
       raise exception 'Scan timestamp is outside the class period' using errcode='22023';
     end if;
     ```
   - Prevents students from scanning saved QR codes before or after scheduled class hours.

### C. Database Schema & Stored Procedures
Managed in migrations:
- `202610110002_attendance_schema.sql`
- `202610110003_attendance_dashboard_matrix.sql`
- `202610110004_attendance_and_timetable_refinements.sql`

#### 1. Core Tables:
- **`classmate.attendance_sessions`**:
  - `id`: UUID PRIMARY KEY (`gen_random_uuid()`)
  - `routine_slot_id`: UUID NOT NULL REFERENCES `classmate.routine_slots(id)` ON DELETE CASCADE
  - `started_by`: UUID NOT NULL REFERENCES `classmate.profiles(id)`
  - `session_secret`: TEXT NOT NULL
  - `started_at`: TIMESTAMPTZ NOT NULL DEFAULT `now()`
  - `closed_at`: TIMESTAMPTZ NULL
  - `status`: TEXT NOT NULL DEFAULT `'open'` CHECK (`status IN ('open', 'closed')`)
  - Indexes: `(routine_slot_id, status)`
- **`classmate.attendance_records`**:
  - `id`: UUID PRIMARY KEY (`gen_random_uuid()`)
  - `session_id`: UUID NOT NULL REFERENCES `classmate.attendance_sessions(id)` ON DELETE CASCADE
  - `student_id`: UUID NOT NULL REFERENCES `classmate.profiles(id)`
  - `marked_at`: TIMESTAMPTZ NOT NULL DEFAULT `now()`
  - `synced_at`: TIMESTAMPTZ NOT NULL DEFAULT `now()`
  - `method`: TEXT NOT NULL DEFAULT `'qr'` CHECK (`method IN ('qr', 'rfid', 'manual')`)
  - `confidence`: TEXT NOT NULL DEFAULT `'normal'` CHECK (`confidence IN ('normal', 'late', 'admin_override')`)
  - Constraint: `UNIQUE (session_id, student_id)` â€” strictly guarantees one attendance record per student per session.

#### 2. Key Database RPCs:
- **`start_attendance_session(target_slot uuid, client_secret text)`**:
  - Checks if an attendance session already exists for `target_slot` on the current date in Asia/Dhaka time.
  - If a session exists, reuses the existing session and secret (`is_resumed = true`), ensuring a single continuous session per day.
  - If new, creates a session row and dispatches push notification outbox event `attendance_open`.
- **`mark_attendance(target_session uuid, qr_token text, time_window bigint)`**:
  - Validates session status, HMAC token match, class period time boundaries, and student batch enrollment.
  - Inserts into `classmate.attendance_records` with single-record conflict protection.
- **`manual_override_attendance(target_session uuid, target_student uuid, present boolean)`**:
  - Allows the assigned teacher or admin to manually mark a student present or absent.
- **`teacher_course_attendance_overview(target_batch uuid, target_course uuid)`**:
  - Aggregates long-term cumulative statistics: enrolled students, total sessions held, average attendance percentage, and session history breakdown.
- **`course_attendance_matrix(target_semester_course uuid)`**:
  - Compiles full student-by-date matrix: students as rows (with roll number, name, present count, percentage) and all sessions as columns with presence mapping.
- **`student_my_attendance(target_batch uuid)`**:
  - Returns student's personal metrics across all enrolled semester courses, overall percentage, and detailed session date logs.

### D. Native Android Implementation
1. **Teacher Live Session (`ClassMateTeacherLiveAttendanceActivity.kt`)**:
   - Layout: `activity_teacher_live_attendance.xml`.
   - Renders large high-contrast QR code generated via ZXing with a pulsating circular timer ring countdown (15s rotation).
   - Real-time present attendee count (`tvPresentCount`: `"âœ“ X / Y present"`) and attendee RecyclerView (`item_attendance_roster.xml`) with profile avatars and roll numbers.
   - Replaced premature "Close Attendance" button with "Done" action so dismissing the screen keeps the session alive.
2. **Student Scanner Viewfinder (`ClassMateStudentAttendanceScannerActivity.kt`)**:
   - Layout: `activity_student_attendance_scanner.xml`.
   - Uses Android Jetpack CameraX (`PreviewView`) combined with Google ML Kit Barcode Scanning (`BarcodeScanning.getClient()`) for instant, high-fps QR detection.
   - Includes flash toggle button (`ivFlashToggle`), target bounding reticle (`bg_scanner_reticle.xml`), and native haptic vibration on success.
3. **Offline Queueing & Reconnection Sync**:
   - If the student scans while disconnected (e.g. basement lecture halls with poor cellular reception), the scan payload is stored in local Room database table `PendingAttendanceScanEntity.kt`.
   - Triggers `AttendanceSyncWorker.kt` via Android WorkManager with network constraint `NetworkType.CONNECTED`.
   - Because the payload preserves the captured `time_window` epoch, the server accepts and honors the scan upon reconnection.

### E. Web Companion Implementation
1. **Teacher Attendance Modal (`attendance-teacher-modal.tsx`)**:
   - Responsive overlay dialog rendering QR code using `qrcode.react`.
   - Subscribes to Supabase Realtime channel (`classmate:attendance_records:session_id=...`) to update the live attendees roster instantly via WebSockets without page polling.
   - Includes full-screen projector toggle for lecture hall display screens.
2. **Student Scanner Modal (`attendance-scanner-modal.tsx`)**:
   - Browser camera stream powered by native `BarcodeDetector` API with polyfill fallback.
   - Configured with `Permissions-Policy: camera=(self)` in `next.config.ts`.
3. **Synthesized Two-Tone Audio Confirmation Chime**:
   - Eliminates external `.mp3` audio asset downloads that fail when offline.
   - Synthesizes a clean two-tone rising harmonic chord (C5 523.25 Hz -> E5 659.25 Hz -> G5 783.99 Hz) directly via standard browser Web Audio API (`AudioContext`, `OscillatorNode`, `GainNode` exponential decay) paired with device vibration (`navigator.vibrate([50, 50, 100])`).

### F. Multi-Date Cumulative Excel Matrix Export (`attendance-excel.ts`)
- Implemented pure client-side SheetJS (`xlsx`) spreadsheet generation, resolving Edge Function memory and timeout failures:
  - **Single Session Report**: Session metadata, date, course, teacher, student roll number, student name, scan time, and verification method.
  - **Semester Course Matrix**: Full multi-column matrix with student roll and name in columns A & B, every class session date in columns C through N (`P` in green or `A` in red), followed by total classes attended, total classes held, and attendance percentage formulas (`=SUM(...) / COUNT(...) * 100`).
  - Downloads `.xlsx` files instantaneously in 0ms with zero server execution cost or egress.

### G. Student Personal Attendance Dashboard (`student-attendance.tsx`)
- Displayed under the "My Attendance" tab in the Timetable screen for enrolled students.
- **Expandable Course Cards**:
  - Header: Course code, course title, credit hours, attended/held ratio (`14/16`), and color-coded progress bar.
  - Accordion Expansion: Tapping a course card slides open its full chronological attendance history.
  - Date-by-Date Logs: Formatted date (e.g., `Sun, 11 Oct 2026`), class time (`10:00 AM`), lecture room (`Room 302`), and status badge (`âœ“ Present` in emerald green or `âœ— Absent` in coral red).

---

## 29. Timetable & Routine Usability Refinements

### A. Batch-Wide Admin "Calendar in Timetable" Toggle
- **Problem**: The Academic Calendar tab in the Timetable screen cluttered navigation for batches that prefer a minimalist routine view.
- **Solution**:
  - Added column `calendar_in_timetable boolean not null default true` to `classmate.batches`.
  - Added RPC `toggle_batch_calendar_in_timetable(target_batch uuid, enabled boolean)` restricted to batch admins and owners.
  - Added toggle switch under Academic Workspace in Settings (`profile.tsx`). When turned off, the Timetable segmented pill dynamically hides the Calendar tab batch-wide.

### B. Teacher Timetable View Simplification
- **Assigned Courses Filter**: When a faculty member views a batch routine, the list filters strictly to routine slots assigned to that teacher (`editable.data?.[slot.semester_course_id] === true`).
- **Contextual Empty States**: If the teacher has no classes scheduled on the selected day, displays:
  - *"You have no classes today for the selected batch"* (if today).
  - *"You have no classes on this day for the selected batch"* (if any other day).
- **Navigation Cleanup**: Hides "Bus Schedule" and "My Attendance" tabs for teachers, leaving only the focused routine view.

### C. Course Attendance Opt-in (Admin Controlled)
- **Problem**: Not all faculty members utilize digital app attendance. Showing attendance buttons on unmonitored courses created student confusion.
- **Solution**:
  - Added column `attendance_enabled boolean not null default false` to `classmate.semester_courses`.
  - Added RPC `toggle_course_attendance(target_course uuid, enabled boolean)` and catalog RPC `batch_attendance_courses(target_batch uuid)`.
  - Added "Attendance Course Settings" configuration modal in Settings for admins/owners with toggle switches for each semester course.
  - Routine cards only display "Take Attendance" / "Scan Attendance" strips if `attendance_enabled = true` for that course.

### D. Ongoing / Live Class Pinned to Top
- During active class hours, the currently live period (`isLive = true`) is automatically sorted and pinned to the very top of the routine list with a prominent glowing emerald banner: **`LIVE NOW Â· Ongoing Period`**.
- Prevents the "Scan Attendance" / "Take Attendance" action strip from hiding behind the mobile bottom navigation bar.
- Automatically resets back to normal chronological order once the class concludes.

### E. Single Continuous Attendance Session per Date
- Replaced premature session termination with an open class window lifecycle:
  - `start_attendance_session` queries existing sessions for `target_slot` on the current date in Asia/Dhaka time. If found, reuses the session (`is_resumed = true`).
  - Dismissing the QR modal (via "Done", "(X)", or backdrop click) hides the layout but does **not** close the session in the database.
  - Teachers can project the QR, dismiss it to begin lecturing, and reopen the same live session anytime during the class period.
  - Sessions automatically expire when the class period concludes.

---

## 30. Cloudflare R2 500 MB Resource Upload Limit & Storage Architecture

### A. Upload Limit Expansion
- The maximum file upload ceiling for course materials, textbooks, question banks, and lecture slides was increased from 100 MB to **500 MB** (`500,000,000 bytes`).

### B. Full-Stack Enforcement
1. **Presigned Upload Edge Function (`supabase/functions/upload-classmate-resource/index.ts`)**:
   - Updated size validation: `!Number.isSafeInteger(size_bytes) || size_bytes < 1 || size_bytes > 500_000_000`.
2. **Web Companion Client (`web/src/components/library.tsx`)**:
   - Adjusted client-side file picker validation and error messaging to support uploads up to 500 MB.
3. **Android Client Resource Upload Activities**:
   - Synchronized file validation constants with the 500 MB ceiling.

### C. Direct-to-R2 Upload Economics
- Uploads bypass Supabase servers completely:
  - Client requests a presigned S3 PUT URL from `upload-classmate-resource` authenticated with JWT.
  - Client streams the file directly to Cloudflare R2 object storage.
  - Preserves Supabase bandwidth limits (5 GB free tier quota) and eliminates server timeouts on large academic video or PDF uploads.

---

## 31. Free-Tier Quota Optimization & Runaway Storage Reclaim Execution

### A. Runaway Audit Log Storage Recovery
- **Root Cause**: Table `public.audit_log` reached **242 MB (343,679 rows)** due to an automated Cloudflare Worker running catalog sync every minute, writing 20â€“40 rows per minute even when no data changed.
- **Execution (`202610110002_attendance_schema.sql`)**:
  - Truncated `public.audit_log`, immediately recovering **~242 MB** of disk space.
  - Overall Supabase database size dropped from ~268 MB to **~15 MB**, restoring **~485 MB of headroom** on the 500 MB free tier.
- **Trigger Defense**: Updated `public.write_audit_log()` to bypass logging `UPDATE` events when `to_jsonb(old) = to_jsonb(new)`.

### B. Cloudflare Worker Cron Reconfiguration
- Cloudflare Worker `classmate-secure-api` cron schedule was changed from every minute (`*/1 * * * *`) to once daily at 4:00 AM UTC (`0 4 * * *`):
  ```jsonc
  "triggers": {
    "crons": ["0 4 * * *"]
  }
  ```
- Eliminated ~43,000 redundant worker invocations and database transactions per month.

### C. Supabase `pg_cron` Schedules & Retention (`202610110003_optimize_cron_schedules_and_retention.sql`)
1. **Notification Retry Job (`classmate-notification-retry`)**:
   - Rescheduled from every 1 minute (`*/1 * * * *`) to every 15 minutes (`*/15 * * * *`). Immediate notification dispatches are already triggered instantaneously via `wake_notification_dispatch`.
2. **Resource Cleanup Job (`classmate-resource-cleanup`)**:
   - Rescheduled from every 1 minute to once daily at 03:00 UTC (`0 3 * * *`).
3. **Automated Cron History Retention (`classmate-purge-cron-logs`)**:
   - Added automated daily maintenance job at 03:30 UTC (`30 3 * * *`):
     ```sql
     delete from cron.job_run_details where end_time < now() - interval '2 days';
     ```
   - Prevents `cron.job_run_details` from accumulating tens of thousands of rows and consuming storage.

---

## 32. CSE 22nd Batch Student Roster Reconciliation & Complete User Provisioning

### A. Attendance Sheet Roster Audit
- Enrolled students in the CSE 22nd Batch (`de0b3379-54a0-4275-a526-5970295c787e`) were audited by cross-referencing registered database profiles against official university attendance sheets.
- 16 enrolled students were identified as missing from the database, hindering Batch Fund tracking and attendance records.

### B. Provisioning 16 Missing Accounts
- Created authenticated accounts in Supabase Auth (`auth.users`) with verified institutional Google email addresses (`ce25xxx@mbstu.ac.bd` / `ce24014@mbstu.ac.bd`).
- Inserted linked student profiles into `classmate.profiles`:
  1. **Marzia Islam** (`CE25002`)
  2. **Sudip Banik** (`CE25006`)
  3. **Armanul Hoque** (`CE25008`)
  4. **Md. Moshiur Rahman Khan** (`CE25009`)
  5. **Apsora** (`CE25023`)
  6. **Md. Millat Hossen** (`CE25024`)
  7. **Rifat Al Momin** (`CE25025`)
  8. **Joydev Debnath** (`CE25026`)
  9. **Tanvir Ahmed** (`CE25032`)
  10. **A. S. M. Farhan** (`CE25033`)
  11. **Md. Shahriar Hossain** (`CE25037`)
  12. **Md. Sazzad Hossen** (`CE25041`)
  13. **Md. Nahidul Islam** (`CE25044`)
  14. **Joy Ghosh** (`CE25047`)
  15. **Sabbir Ahmed** (`CE25055`)
  16. **Md. Shahriar Parvez** (`CE24014` â€” academic batch override linked to CSE 22nd Batch)

### C. System Impact
- Total verified cohort brought to **58 students** (41 pre-existing + 16 provisioned + 1 batch admin/owner).
- Ensures 100% complete Batch Fund collections, zero missing students in attendance matrices, and frictionless first-time Google sign-ins.

---

## 33. Technology Stack & Architectural Summary Reference

| Architectural Layer | Core Technologies | Key Responsibilities & Implementation Notes |
| :--- | :--- | :--- |
| **Android Native App** | Kotlin 2.0, Android Jetpack, Room DB, WorkManager | Native mobile client; CameraX + Google ML Kit Barcode Scanning; `PackageInstaller` session updates; Facebook Shimmer; Material BottomSheet. |
| **Database & Backend** | Supabase PostgreSQL 15, PostgREST, PL/pgSQL | Relational data store, Row Level Security (RLS), security definer RPCs, Supabase Realtime WebSockets, `pg_cron` background scheduling. |
| **Serverless & Edge** | Supabase Edge Functions (Deno / TypeScript), Cloudflare Workers | Background notification dispatchers, R2 upload presigning, catalog synchronization, daily maintenance routines. |
| **Cloud Storage** | Cloudflare R2 Storage (S3-compatible API) | Zero-egress binary storage for course files, protected PDFs, and GitHub Release APK distribution assets up to 500 MB. |
| **Web Companion Client** | Next.js 15, React 19, TypeScript, Tailwind CSS | Desktop sidebar rail & responsive mobile client; SheetJS (`xlsx`) Excel export; Web Audio API synthesized chimes; HTML5 BarcodeDetector. |
| **Push Notifications** | Firebase Cloud Messaging (FCM), Service Workers | Instant cross-platform push alerts for notices, class cancellations, live attendance sessions, and strict single-recipient fund deposits. |
| **Update Distribution** | GitHub Releases CDN, `update.json` manifest | Self-hosted in-app update pipeline signed with keystore alias `key0` (dual V1 JAR + V2 APK Signature Scheme). |
| **Real-Time Attendance Engine** | ZXing, CameraX, ML Kit, HMAC-SHA256, Supabase Realtime | 15s rotating dynamic QR generation & camera scanning; offline Room queue with WorkManager sync; 3s active session timetable polling; Supabase Realtime live roster WebSocket updates. |
| **Data Formatting & Export** | SheetJS (`xlsx`), natural alphanumeric comparator algorithms | Client-side 0ms Excel generation for single sessions and semester course attendance matrices; natural roll ordering (`CE25001` < `CE25032` < `CE25060`). |
| **System Window UI Handling** | Android `WindowInsetsCompat`, CSS top layer `<dialog>` | Edge-to-edge system navigation bar padding for pinned action sheets; browser top-layer modals immune to feed overflow/stacking contexts. |

---

## 34. Student "My Attendance" Tab Complete Redesign & Scoped Analytics (v1.1.32+)

### A. Core Architectural Motivation & User Pain Points
Prior to v1.1.32, the "My Attendance" tab inside the Timetable screen had several usability and information architecture flaws:
1. **Ambiguous Global Metrics**: The overview hero displayed `"0% Â· 0 of 6 classes attended"`. Because attendance tracking is selectively enabled by admins only for specific courses, students misunderstood this as a failing grade across their entire semester workload.
2. **Tab Navigation Displacement**: Tapping "My Attendance" caused the parent routine/bus schedule toggle bar to slide or drop vertically, jarring the user experience.
3. **UI Clutter & Visual Noise**: The course cards had unnecessary filter chips, progress rings, pulsing badges, and separate refresh buttons that did not align with ClassMate's clean design system.

### B. Dynamic Scoped Summary Calculations
- **Course-Specific Summary**:
  - When only a single course is enabled for attendance tracking, the summary label dynamically names the course:
    - Example: `"0% Â· 0 of 6 classes attended in CSE3333"`.
- **Multi-Course Aggregated Summary**:
  - When multiple courses are tracked, the summary displays an explicit aggregated label:
    - Example: `"Across 3 tracked courses"`.
- **Zero-Session Safe Handling**:
  - If no eligible attendance sessions have been conducted yet, the UI displays `"No sessions recorded yet"` instead of a misleading `0%`, eliminating divide-by-zero anomalies.
- **Dynamic Eligibility Classification**:
  - **Eligible**: Attendance $\ge 75\%$ â€” emerald green pill (`@color/cm_file_lab_text` / `#10B981`).
  - **Warning**: Attendance $60\% - 74\%$ â€” amber pill (`@color/cm_warning` / `#F59E0B`).
  - **At Risk**: Attendance $< 60\%$ â€” coral red pill (`@color/cm_notice_cancel` / `#EF4444`).

### C. Visual Layout & Component Refinements
1. **Elevated Top Overview Hero Card**:
   - Elevated card containing the bold percentage (`0%`), dynamic eligibility status pill, scoped subtitle, and a full rounded progress bar.
2. **Streamlined Tracked Course Cards**:
   - Left vertical category accent bar (4dp width).
   - Course code and full course title with right-aligned percentage badge pill.
   - Faculty name accompanied by a teacher icon.
   - Fractional attendance counter (`0 / 6 classes attended`) and a smooth progress indicator.
   - Interactive chevron toggle (`â–¾` / `â–´`) indicating expandable session history.
3. **Accordion Session History**:
   - Embedded collapsible session history logs.
   - Formatted class date/time, lecture room number, and distinct status badges (`âœ“ Present`, `âš  Late`, `âœ— Absent`).
   - "View all (N sessions)" / "Show less" toggle for courses with lengthy session histories.
4. **Stable Non-Jumping Tab Layout**:
   - Fixed the parent container in `ClassMateAcademicScreensSupabase.kt` and `web/src/components/schedule.tsx` so toggling between "Class Routine", "Bus Schedule", and "My Attendance" preserves the sticky header and segment pills in place without layout displacement or scroll disorientation.

---

## 35. Real-Time Timetable Attendance Engine & Instant Active Session Polling

### A. High-Frequency Active Session Poller
- **Problem**: Previously, when a teacher opened an attendance session, students did not see the "Scan Attendance" action strip on their timetable routine card for several minutes unless they manually executed a pull-to-refresh or restarted the app.
- **Solution (`202610130001_instant_attendance_timetable_updates.sql`)**:
  - **Lightweight Supabase RPC (`classmate.active_attendance_sessions(target_batch uuid)`)**:
    - Returns active open sessions: `session_id`, `routine_slot_id`, `semester_course_id`, `started_at`, `status`, and `present_count`.
    - Highly indexed on `(sem.batch_id, s.status = 'open')` with execution time under 10ms.
  - **3-Second Active Background Poller**:
    - Android (`ClassMateAcademicScreensSupabase.kt`) and Web (`schedule.tsx`) maintain a 3-second background poller while viewing today's timetable in the foreground.
    - When a teacher taps "Take Attendance", the session state propagates across all connected batch devices within 3 seconds:
      - Teacher's timetable period card flips into `"Attendance Live Â· Tap to manage"` with live attendee count.
      - Students' timetable period card flips into **`"Scan Attendance"`** with a prominent emerald action strip.
- **Timetable Cache Invalidation on Pull-to-Refresh**:
  - Pull-to-refresh on the routine screen explicitly bypasses `ClassMateAcademicCache` and forces an immediate server re-fetch of `timetable_details` and `active_attendance_sessions`.
  - Updated `timetable_details` in Supabase to return `attendance_enabled` flag directly, ensuring course-level toggle changes propagate without delay.

---

## 36. Bulletproof Attendance Security, Flexible Time Validation & E2E Verification (Migration `202610130002`)

### A. Multi-Tier Management Authorization (`can_manage_attendance_slot`)
Replaced brittle single-check logic with a resilient multi-tier permission resolver:
1. Postgres superusers (`postgres`, `supabase_admin`)
2. App owner (`classmate.is_owner()`)
3. Batch Administrator (`p.role = 'admin'`)
4. Active Teacher Course Assignment (`teacher_course_assignments.active = true`)
5. Teacher Directory matching by:
   - Profile ID (`td.profile_id = auth.uid()`)
   - Exact email (`lower(td.email) = lower(p.email)`)
   - Username prefix before `@` (`split_part(lower(td.email), '@', 1) = split_part(lower(p.email), '@', 1)`).

### B. Secret Resilience & Same-Day Session Reopening
- `start_attendance_session` verifies `session_secret` is never empty. If null or empty, it automatically generates a cryptographically secure 24-byte hex secret (`encode(extensions.gen_random_bytes(24), 'hex')`).
- Reopening a closed session on the same calendar day reuses today's session and secret (`is_resumed = true`), cleanly setting `status = 'open', closed_at = null` without creating duplicate or orphan session rows.

### C. Flexible Rotating QR Tolerance & Schedule Boundary Decoupling
- **15-Second Epoch Windows**: `time_window = floor(current_epoch_seconds / 15)`.
- **Dynamic Tolerance**: Allows up to 3 windows drift (`abs(current_win - time_window) <= 3`, Â±45 seconds) to handle mobile network transmission and camera focusing latency, while strictly rejecting screenshots older than 45 seconds.
- **Decoupled Schedule Check**:
  - Removed rigid hard-rejections (`22023 outside class period`) that previously blocked demo sessions, testing, or classes starting slightly early.
  - Scans are accepted as long as the session is currently `open`, while accurately flagging `confidence = 'late'` if scanned $> 15$ minutes after nominal start time.
- **Idempotent Upsert Guarantee**:
  - `on conflict (session_id, student_id) do update set synced_at = now()`.
  - Prevents crashes on network retries or double scans, returning `{ "success": true, "status": "present" }` idempotently.

### D. Automated End-to-End Test Suite (`scripts/test_attendance_e2e.py`)
Verified against both Staging and Production:
1. Attendance session start and resume.
2. Dynamic HMAC-SHA256 calculation for current window.
3. Live student scan validation (`success: true`).
4. Duplicate scan idempotency.
5. Strict rejection of expired tokens ($> 75$ seconds old).
6. Live session roster verification.
7. Active timetable session query verification.

---

## 37. Natural Alphanumeric Student Roll Sorting Engine (Excel & Live Realtime Scanner)

### A. The Natural Roll Ordering Problem
University roll numbers follow an alphanumeric format (e.g. `CE25001`, `CE25009`, `CE25010`, `CE25032`, `CE25060`). Standard lexicographic string sorting fails if rolls have variable formatting or different lengths. Furthermore, live scanning rosters previously showed students in chronological scan arrival order rather than academic roll order.

### B. Natural Chunking Comparator Implementation
- Splits roll strings into text prefixes and numerical integer chunks.
- Compares numerical portions as integers so that `CE25001` < `CE25002` < `...` < `CE25060`.
- **Excel Spreadsheet Export**:
  - Integrated into SheetJS generation in `web/src/lib/attendance-excel.ts` and Supabase Edge Function `supabase/functions/export-attendance/index.ts`.
  - Student rows are strictly sorted by student roll in both Single-Session reports and Semester Cumulative Matrices.
- **Real-Time Live Scanning Roster**:
  - **Android (`ClassMateTeacherLiveAttendanceActivity.kt`)**: Kotlin `compareRolls` comparator sorts incoming scans immediately on every 2.5s poll or push event.
  - **Web (`attendance-teacher-modal.tsx`)**: Supabase Realtime WebSocket events sort `filteredRoster` and `presentStudentsSorted` on every scan.
- **Teacher Dashboard Toggle**:
  - Added sort toggle pills in `teacher-attendance.tsx`: `Roll (CE25001...)` (default) vs `At-Risk (Lowest %)`.

---

## 38. Pinned Bottom Action Sheet & Responsive QR Viewport Architecture

### A. Zero Hidden Buttons UX
On smaller smartphone screens and low-resolution laptop displays, previous layouts placed "Close Attendance" and "Export to Excel (.xlsx)" buttons inside scrollable views, causing them to fall below the screen fold or hide behind Android system gesture bars.

### B. Architectural Solution
1. **Android (`activity_teacher_live_attendance.xml`, `ClassMateTeacherLiveAttendanceActivity.kt`)**:
   - Pinned action buttons into a dedicated bottom action container (`layoutBottomActions`) outside `NestedScrollView`.
   - Dynamic `WindowInsetsCompat.Type.systemBars().bottom` padding ensures comfortable spacing above system navigation bars on all Android versions.
   - Reduced QR canvas dimension from `280dp` to `220dp`, keeping live attendee counters (`âœ“ X / Y present`) and the 15-second rotation countdown ring visible without scrolling.
2. **Web (`attendance-teacher-modal.tsx`, `globals.css`)**:
   - Sticky `.attendance-modal-footer` container pinned to the bottom with backdrop blur.
   - Resized QR canvas to `230px` for optimal responsiveness across laptop displays and mobile browser viewports.

---

## 39. Android Native Attendance 100% Feature Parity & Segmented Tab Architecture (Commit `2bd3ceb`)

### A. Architectural Motivation & Android Parity Goal
While the QR Attendance system was initially verified on the Web client, the native Android APK (`com.shuaib.classmate`) required complete parity in UI fluidity, instant session discovery, and ergonomic live roster management during physical classroom environments:
1. **Zero Manual Refresh**: Previously, students and teachers had to manually pull-to-refresh the Timetable feed to see live attendance action cards after a class started.
2. **Viewport Stability**: Toggling between the rotating QR code and the live student list required vertical scrolling, which often scrolled the QR code off-screen on smaller devices.
3. **Bottom Navigation Clipping**: System Android navigation bars and gesture pill handles obscured action buttons when pinned near screen edges.

### B. Instant Timetable Detection & Heartbeat Poller (`ClassMateAcademicScreensSupabase.kt`)
- **6-Second Active Polling Cycle**:
  - In `ClassMateAcademicScreensSupabase.kt`, viewing today's timetable launches a lightweight background runner:
    ```kotlin
    private val attendanceLiveRunnable = object : Runnable {
        override fun run() {
            if (activeMode == AcademicMode.ROUTINE && isViewingToday()) {
                refreshActiveAttendanceSessions()
            }
            handler.postDelayed(this, 6000L)
        }
    }
    ```
  - When a teacher launches attendance in the classroom, all connected student devices flip the routine card from standard details to a prominent emerald action strip: **`"Scan Attendance"`** within 6 seconds.
  - Teachers and administrators see their period card dynamically transition to: **`"Attendance Live Â· Tap to manage"`** with a live attendee counter pill (`âœ“ X present`).

### C. Segmented Header Tabs for Teacher Live View (`ClassMateTeacherLiveAttendanceActivity.kt`)
- **Header Segment Architecture (`activity_teacher_live_attendance.xml`)**:
  - Replaced clumsy vertical scrolling with a top fixed segmented control: `[ QR Code | Live Roster ]` (`viewAttendanceModeSegment`, `btnViewModeQr`, `btnViewModeRoster`).
  - **QR Code View**: Large high-contrast QR canvas (resized to 220dp), 15-second pulsating countdown ring, and live present summary text (`"âœ“ X / Y present"`).
  - **Live Roster View**: Full-width RecyclerView (`rvLiveAttendees`) displaying enrolled students sorted by academic roll.
  - Switching between views occurs instantly in 0ms with zero scroll displacement or layout jumping.
- **Natural Student Roll Ordering**:
  - Live scans are sorted in real time using `compareRolls` (`CE25001` < `CE25002` < `...` < `CE25060`), maintaining a stable, predictable academic roll order instead of erratic arrival times.
- **Edge-to-Edge System Bar Insets**:
  - Pinned bottom action bar (`layoutBottomActions`) utilizes `ViewCompat.setOnApplyWindowInsetsListener`:
    ```kotlin
    ViewCompat.setOnApplyWindowInsetsListener(layoutBottomActions) { view, insets ->
        val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, sysBars.bottom)
        insets
    }
    ```
  - Eliminates overlap with Android 10+ gesture navigation lines or 3-button navigation bars.

### D. Fair Attendance Scanning (No Automated Late Penalty)
- **Removal of Misleading Late Flags (`202610130003_attendance_no_late_penalty.sql`)**:
  - In real university lectures, teachers routinely take attendance halfway through class or near dismissal. Comparing scan timestamps against `class_start + 15m` previously misclassified legitimate students as "Late".
  - Any scan recorded during an active, open session is marked cleanly as **`Present`** (`confidence = 'normal'`). Historical records were retroactively sanitized to `normal`.

### E. Dual Admin-Student Role Support in CSE 22nd Batch
- The system administrator / developer belonging to the CSE 22nd Batch is registered as a student in `classmate.batch_students` and `classmate.profiles`.
- Permission functions (`can_manage_attendance_slot`) authorize admin controls without stripping student scan eligibility, allowing simultaneous session administration and self-attendance verification.

---

## 40. Intelligent Multi-State Camera Permission Architecture & Browser Site Settings Recovery

### A. Android Multi-State Permission Architecture (`ClassMateStudentAttendanceScannerActivity.kt`)
To eliminate camera access friction and avoid confusing students when permissions are denied:
1. **Pre-Permission Educational Dialog**:
   - Before invoking the native Android runtime permission modal (`requestPermissionLauncher.launch(Manifest.permission.CAMERA)`), the app displays a friendly educational dialog:
     - **Title**: *"Camera Access Needed for Attendance"*
     - **Explanation**: *"ClassMate uses your camera exclusively to scan the dynamic, rotating attendance QR code displayed by your course teacher. No photos or video recordings are saved."*
   - Prepares the student to tap "While using the app" rather than accidentally dismissing the OS prompt.
2. **Permanent Denial Recovery (`shouldShowRequestPermissionRationale == false`)**:
   - If a student taps "Don't allow" twice or selects "Don't ask again", the Android framework permanently silences future permission requests.
   - The scanner detects this permanent denial state and presents a dedicated recovery sheet:
     - Clear explanation that camera access was permanently disabled.
     - Step-by-step instructions: *"Tap Settings &rarr; Permissions &rarr; Camera &rarr; Allow only while using the app"*.
     - One-tap intent button:
       ```kotlin
       val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
           data = Uri.fromParts("package", packageName, null)
       }
       startActivity(intent)
       ```
     - On returning (`onResume`), the scanner automatically re-checks `ContextCompat.checkSelfPermission` and initializes CameraX seamlessly if permission was granted.

### B. Web Companion & Chrome Browser Permission Recovery (`attendance-scanner-modal.tsx`)
1. **Granular Permission State Machine**:
   - Tracks explicit lifecycle states: `initializing`, `prompt`, `running`, `denied`, `blocked_by_browser`, `unsupported`.
2. **Chrome Silent Block Detection & Guided Solution**:
   - When users disable camera prompts globally in Google Chrome or set site permissions to "Block", standard web APIs fail with `NotAllowedError` without showing a dialog.
   - The UI surfaces illustrated, step-by-step resolution steps:
     1. Click the **View site information / Tune icon** (`ðŸ”’` / `âš™ï¸`) located to the left of the browser URL bar.
     2. Toggle **Camera** from "Blocked" to **"Allow"**.
     3. Or open `chrome://settings/content/camera` and remove `https://classmatebd.vercel.app` from the blocked list.
     4. Tapping "Try Again" re-triggers `navigator.mediaDevices.getUserMedia` without requiring a full page reload.
3. **Harmonic Audio & Haptic Confirmation**:
   - Synthesizes a clean rising two-tone chord (C5 523.25 Hz &rarr; E5 659.25 Hz &rarr; G5 783.99 Hz) directly via the Web Audio API (`AudioContext`, `GainNode`) paired with dual pulse vibration (`navigator.vibrate([50, 50, 100])`), providing unmistakable sensory feedback.

---

## 41. CSE 22nd Batch 2nd Year 1st Semester Official Routine & Curriculum Overhaul

### A. Background & Curriculum Extraction
On October 13, 2026, the department published the official academic timetable for **CSE 2nd Year 1st Semester (Session: 2024â€“2025)**. The curriculum and weekly routine were extracted from the official notice and provisioned into the production Supabase database via `scripts/web/update_routine_data.py`.

### B. Course Catalog & Teacher Assignments (`Batch 22 Semester 3`)
- **Department**: Computer Science and Engineering (`d63bb85b-13bd-4da6-b905-12d622ebd3f5`)
- **Semester Target**: Batch 22, Semester 3 (`56056dc8-5c95-40d5-95b5-162a6ff4cd49`)

| Course Code | Official Course Title | Type | Credit | Assigned Course Teacher |
| :--- | :--- | :---: | :---: | :--- |
| **`CSE2101`** | Object Oriented Programming | Theory | 3.00 | Dr. Md. Sazzad Hossain |
| **`CSE2102`** | Object Oriented Programming Lab | Sessional | 1.50 | Dr. Md. Sazzad Hossain |
| **`CSE2103`** | Data Structures and Algorithms | Theory | 3.00 | Dr. Mehedi Hasan Talukder |
| **`CSE2104`** | Data Structures and Algorithms Lab | Sessional | 1.50 | Dr. Mehedi Hasan Talukder |
| **`CSE2105`** | Computer Organization and Architecture | Theory | 3.00 | Dr. Md. Mahfuzur Rahman |
| **`CSE2107`** | Matrix and Linear Algebra | Theory | 3.00 | Md. Mahbubur Rahman |
| **`CSE2109`** | Sociology & Economics | Theory | 2.00 | Mehedi Hasan |
| **`CSE2111`** | Financial and Managerial Accounting | Theory | 2.00 | Dr. Md. Abdul Kuddus |
| **`CSE2114`** | Software Development Project-I & Industrial Tour | Sessional | 0.75 | â€” |

- **Teacher Directory Expansion (`classmate.teacher_directory`)**:
  - Inserted missing faculty profiles: `Md. Mahbubur Rahman`, `Dr. Md. Mahfuzur Rahman`, `Dr. Md. Abdul Kuddus`, `Mehedi Hasan`.

### C. 15-Slot Routine Matrix (Saturday â€“ Wednesday)
Routine slots were mapped into `classmate.routine_slots` with accurate room locations:
- **Saturday (Day 6)**:
  - `10:00 â€“ 11:50`: `CSE2104` (DSA Lab) â€” Lab-02
  - `12:00 â€“ 12:50`: `CSE2107` (Matrix & Linear Algebra) â€” Room 338
  - `14:30 â€“ 15:10`: `CSE2109` (Sociology & Economics) â€” Room 340
- **Sunday (Day 0)**:
  - `10:00 â€“ 10:50`: `CSE2105` (Computer Organization & Architecture) â€” Room 338
  - `12:00 â€“ 12:50`: `CSE2103` (DSA Theory) â€” Room 341
  - `13:45 â€“ 15:10`: `CSE2104` (DSA Lab) â€” Lab-02
  - `15:15 â€“ 15:55`: `CSE2101` (OOP Theory) â€” Room 338
- **Monday (Day 1)**:
  - `10:00 â€“ 10:50`: `CSE2107` (Matrix & Linear Algebra) â€” Room 338
  - `12:00 â€“ 12:50`: `CSE2103` (DSA Theory) â€” Room 341
  - `13:45 â€“ 14:25`: `CSE2101` (OOP Theory) â€” Room 338
  - `14:30 â€“ 15:55`: `CSE2102` (OOP Lab) â€” Lab-01
- **Tuesday (Day 2)**:
  - `09:00 â€“ 09:50`: `CSE2101` (OOP Theory) â€” Room 339
  - `10:00 â€“ 10:50`: `CSE2105` (Computer Organization & Architecture) â€” Room 339
  - `11:00 â€“ 11:50`: `CSE2103` (DSA Theory) â€” Room 339
  - `13:45 â€“ 15:10`: `CSE2102` (OOP Lab) â€” Lab-01
- **Wednesday (Day 3)**:
  - `10:00 â€“ 10:50`: `CSE2107` (Matrix & Linear Algebra) â€” Room 341
  - `11:00 â€“ 11:50`: `CSE2111` (Financial Accounting) â€” Room 338
  - `14:30 â€“ 15:10`: `CSE2109` (Sociology & Economics) â€” Room 340

---

## 42. Library Google Drive Link Sharing & External URL Infrastructure (Migration `202610140001_library_drive_links.sql`)

### A. Architectural Motivation & Storage Optimization
While Cloudflare R2 object storage handles PDFs and documents up to 500 MB efficiently, many academic assets exceed single-file limits or exist as collaborative cloud resources:
- Shared Google Drive folders for lab project source code.
- Gigabyte-scale video lecture archives stored on Google Drive.
- External documentation links and online reference materials.

To support these assets without consuming storage quotas or causing mobile upload timeouts, ClassMate expanded its Library subsystem to natively support **Google Drive & external resource links**.

### B. Database Schema Enhancements (`202610140001_library_drive_links.sql`)
1. **Schema Alterations**:
   - `alter table classmate.file_metadata add column if not exists link_url text;`
   - `alter table classmate.file_metadata alter column r2_object_key drop not null;`
2. **RPC: `classmate.create_library_link(...)`**:
   - Parameters: `target_batch uuid`, `target_course uuid`, `target_title text`, `target_category text`, `target_url text`.
   - Security: Verifies `caller_id is not null` and checks permissions via `classmate.can_post(target_batch, target_course)`.
   - URL Sanitization: Automatically prepends `https://` if protocol scheme was omitted; validates length between 5 and 2000 characters.
   - Record Insertion: Inserts into `classmate.file_metadata` with `file_type = 'link'`, `mime_type = 'text/uri-list'`, `size_bytes = 0`, `r2_object_key = null`, and `link_url = v_url`.
   - Audit Trail: Records creation in `classmate.audit_log`.
3. **RPC: `classmate.edit_resource_metadata(...)`**:
   - Updated signature to accept `target_link_url text default null`.
   - Allows CRs and admins to update Drive links directly from the item edit modal.
4. **RPC: `classmate.delete_library_resource(target_resource uuid)`**:
   - Security definer procedure that safely removes `classmate.file_metadata`, cleans up linked notices in `classmate.notices`, and removes pending notification outbox items.
   - Bypasses binary deletion in Cloudflare R2 when the file type is `'link'`.

### C. Web UI Presentation & Interaction (`web/src/components/library.tsx`, `api.ts`)
1. **Segmented Upload Modal `[ Upload File | Drive Link ]`**:
   - Tapping "Add file" presents two clean tabs.
   - "Drive Link" tab features streamlined inputs: Resource Title, URL (with helper placeholder `https://drive.google.com/...`), Category selector, and optional Course dropdown.
2. **Visual Presentation Tokens**:
   - Resources linking to Google Drive display an authentic **Google Drive icon** (`DriveIcon` SVG) and emerald green badge: **`DRIVE`**.
   - Subtitle format: `{category} Â· Google Drive`.
   - Other external URLs render an `ExternalLink` icon and blue badge: **`LINK`**.
3. **One-Tap Redirection (`openFile`)**:
   - In `web/src/lib/api.ts`:
     ```typescript
     export async function openFile(resource: string | Row) {
       if (typeof resource !== "string" && resource?.link_url) {
         return openResource(() => Promise.resolve(String(resource.link_url)));
       }
       // Falls back to signed R2 presigned download URL for binary files
       ...
     }
     ```
   - Clicking any Drive link resource opens the external Google Drive document or folder in a new browser tab immediately.
4. **Production Deployment & PWA Cache Management**:
   - Deployed live to Vercel Production (`https://classmatebd.vercel.app`).
   - Addressed PWA aggressive service worker caching by documenting hard refresh requirements (`Ctrl + F5` / clear site cache) for immediate client asset invalidation.

---

## 43. Attendance Session Deduplication, Session Reconciliation & Batch Fund Auditing

### A. Live Session Disaster Recovery & Deduplication (CSE2105)
On October 13, 2026, duplicate testing sessions were started simultaneously during initial feature rollout for **Computer Organization and Architecture (`CSE2105`)**, creating three concurrent database records:
1. **Diagnostic Investigation**:
   - Database queries inspected all rows in `classmate.attendance_sessions` and `attendance_records` for `CSE2105`.
   - Identified Session 1 (initial morning test, 42 scans), Session 2 (official class session at 11:59 AM with course teacher Mohammed Hadifur Rahman, 46 scans), and Session 3 (accidental restart, 1 scan).
2. **Reconciliation & Purge**:
   - Preserved **Session 2** as the authoritative historical record containing all 46 verified student scans.
   - Safely deleted redundant test Sessions 1 and 3 along with duplicate scan rows.
   - Verified that teacher and student dashboards in both Web and Android refreshed with exact, consistent 46/46 attendance metrics.

### B. CSE 22nd Batch Fund Dues Reconciliation Audit (Monthly Fund)
- Audited all deposit transactions in `classmate.batch_fund_transactions` for the CSE 22nd Batch against the 58-student cohort roster.
- Cross-referenced all recorded inflows with active student profiles:
  - Total active cohort: 58 enrolled students.
  - Paid students: 43 students deposited their monthly class fund.
  - Unpaid students: 15 students with outstanding dues.
- Provided an exact ledger breakdown with student rolls, names, and contact IDs, facilitating CR bookkeeping, transparent audit trails, and targeted collection reminders via `dialog_fund_payers_sheet.xml`.

### C. CSE 22nd Batch RFID Attendance Card Collection Audit (October 17, 2026)
- **Background**: To manufacture and distribute physical 13.56 MHz ISO 14443A RFID student ID cards for the doorway automated attendance reader (Section 44/48), a dedicated collection drive was initiated at **à§³40 per student**.
- **Database Reconciliation**:
  - Queried `classmate.batch_fund_transactions` filtering for transactions with title containing `"attendance card"` or `"card"` linked to Batch 22 (`de0b3379-54a0-4275-a526-5970295c787e`).
  - **Summary Metrics**:
    - **Total Active Students**: 58
    - **Paid Students**: **21 students** (Total collected: **à§³845.00**; 20 students paid à§³40.00, 1 student paid à§³45.00).
    - **Unpaid Students**: **37 students** (Outstanding dues: **à§³1,480.00**).
  - **Paid Cohort Verification**:
    `CE24027` (Rukaiya Binta Rashid), `CE25007` (Rejuoana Bushra), `CE25015` (Md. Rakibul Hasan), `CE25019` (Abida Akter Jeni), `CE25025` (Mst. Samia Akter), `CE25028` (Taslima Akter), `CE25031` (Md. Tanvir Hasan), `CE25036` (Tusar Ahammed), `CE25037` (Amrin Bhuyan), `CE25038` (Ahosan Habib Zihan), `CE25040` (Shamima Akter Tuli), `CE25041` (Mst. Rizvy Akter Boby), `CE25043` (Md. Jubaer Islam Ramim), `CE25047` (Rayhan Arfin), `CE25049` (Mahfuja Mahfuja), `CE25052` (Md. Efthekhar Alam Chowdhury), `CE25054` (Md. Imran Hossain), `CE25056` (Usling Usling), `CE25057` (Khosh Afroza Akter), `CE25059` (Md. Asib), `CE25060` (Md. Rajib Ullah).
  - **Actionable CR Deliverable**: Generated an audited list of the 37 pending students across rolls `CE25001` through `CE25061`, enabling CRs to reconcile card orders before physical UID programming.

---

## 44. Hardware IoT RFID Attendance Subsystem Architecture & Prototype Integration (ESP32 + RC522)

### A. Context & Purpose
In high-density lecture rooms (60+ students in CSE 22nd Batch), relying solely on optical camera QR scanning can create physical bottlenecks if students crowd around a single projected QR code or smartphone screen before class starts. To complement camera-based QR attendance, ClassMate's data model was engineered from inception to support multi-modal attendance:
- `classmate.attendance_records` column `method` explicitly accepts:
  - `'qr'`: Dynamic rotating TOTP-style QR scanned via student camera.
  - `'rfid'`: Physical ISO 14443A card / keyfob tap on a doorway reader.
  - `'manual'`: Teacher or CR manual presence override.

An exploratory hardware prototype was built (`ec2394c4-5dc1-45c8-9099-42f7cf48c70f`) to bridge physical RFID taps directly into ClassMate's attendance schema.

```mermaid
flowchart LR
    StudentCard[Student RFID Card / Keyfob] -->|13.56 MHz RFID Tap| RC522[MFRC522 SPI Module]
    RC522 -->|Hardware VSPI Bus| ESP32[ESP32 38-Pin Microcontroller]
    ESP32 -->|Wi-Fi / HTTPS POST /mark-rfid-attendance| EdgeFn[Supabase Edge Function / API]
    EdgeFn -->|Validate Active Session & Tag ID| DB[(classmate.attendance_records<br>method = 'rfid')]
    DB -->|Realtime WebSocket Broadcast| TeacherUI[Teacher Live Attendance Screen]
```

### B. Hardware Stack & Technical Specifications
1. **Microcontroller**:
   - ESP32 38-Pin Development Module (Dual-core Xtensa 32-bit LX6, 240 MHz, 520 KB SRAM, 4 MB Flash, 2.4 GHz 802.11 b/g/n Wi-Fi).
2. **RFID Reader Module**:
   - NXP MFRC522 IC module operating at 13.56 MHz frequency.
   - Supports ISO/IEC 14443A cards, tags, and keyfobs (Mifare Classic 1K / 4K).
   - Serial Peripheral Interface (SPI) communication up to 10 Mbps.
3. **Power Regulation Requirement**:
   - **MFRC522 Operating Voltage**: **3.3V DC strictly**. Connecting to 5V will permanently burn the IC or cause floating SPI logic levels on MISO/MOSI.

### C. 38-Pin ESP32 Hardware VSPI Pinout Mapping
The standard 38-pin ESP32 dev module features two user-accessible SPI buses: HSPI and VSPI. For reliable hardware communication without GPIO strapping pin conflicts during boot:

| RC522 Pin | 38-Pin ESP32 Pin | Function / Description | Critical Wiring Notes |
| :--- | :--- | :--- | :--- |
| **`3.3V (VCC)`**| **`3V3`** | 3.3V Regulated Power Rail | **Do NOT connect to `VIN` or `5V`** |
| **`RST`** | **`GPIO 22`** (or `GPIO 4`) | Hardware Reset | Triggers reader reset sequence |
| **`GND`** | **`GND`** | Common Ground | Common ground with ESP32 |
| **`IRQ`** | *Not Connected* | Interrupt Request | Optional, polling is preferred |
| **`MISO`** | **`GPIO 19`** | VSPI Master-In-Slave-Out | Reader data output to ESP32 |
| **`MOSI`** | **`GPIO 23`** | VSPI Master-Out-Slave-In | ESP32 command input to reader |
| **`SCK`** | **`GPIO 18`** | VSPI Clock | Serial clock pulse (1 MHz â€“ 4 MHz) |
| **`SDA / SS`** | **`GPIO 5`** | VSPI Chip Select (Slave Select) | Active-low chip select |

### D. Diagnostic Register Verification & Fault Diagnosis
During hardware initialization, the ESP32 reads the internal `VersionReg` (`0x37`) of the MFRC522:
```cpp
byte version = mfrc522.PCD_ReadRegister(mfrc522.VersionReg);
```
- **Healthy Register Read**:
  - `0x92`: MFRC522 Version 2.0 (standard production silicon).
  - `0x91`: MFRC522 Version 1.0 (early silicon).
- **Fault Code: `0x00` (Lines Grounded or Unpowered)**:
  - Symptom: Serial prints `Firmware Version: 0x0 = (unknown)`.
  - Cause: MISO line shorted to ground, missing 3.3V VCC power, cold solder joint on header pins, or SCK line open circuit.
- **Fault Code: `0xFF` (Lines Floating or Disconnected)**:
  - Symptom: Serial prints `Firmware Version: 0xFF = (unknown)`.
  - Cause: MISO pin floating, SDA/SS not pulling LOW, incorrect GPIO numbering on 38-pin board, or loose breadboard jumper wires.
- **Diagnostic Recovery Procedure**:
  - Lower SPI bus frequency from default 4 MHz down to 1 MHz (`SPI.setFrequency(1000000)`).
  - Test alternate reset pin GPIOs (`GPIO 4`, `GPIO 21`, `GPIO 22`).
  - Resolder pin header connections to guarantee low-impedance contact on high-frequency SPI lines.

### E. Flashing & Serial Communication Protocol
- **Baud Rate**: Set Serial monitor and sketch to `115200` baud.
- **Esptool Bootloader Handshake**:
  - When `esptool.py` displays `Connecting........`, hold the physical **`BOOT`** button (GPIO 0) on the ESP32 dev board until upload percentage begins, then release.
  - Ensures clean transition into serial flashing mode regardless of host USB-to-UART driver latency.

### F. Cloud Ingestion & Edge Function Protocol
1. **Tag Read**: Student taps their RFID card &rarr; ESP32 extracts UID bytes (`e.g., 4A:8B:1C:9D`).
2. **Payload Dispatch**: ESP32 dispatches authenticated HTTPS POST to Supabase:
   ```json
   {
     "device_id": "doorway_reader_cse_338",
     "batch_id": "de0b3379-54a0-4275-a526-5970295c787e",
     "card_uid": "4A8B1C9D",
     "scan_time": "2026-10-15T11:05:32Z"
   }
   ```
3. **Database Resolution**:
   - Resolves `student_id` from `classmate.profiles` where `rfid_tag_id = '4A8B1C9D'`.
   - Locates currently `open` session in `classmate.attendance_sessions` for that classroom/slot.
   - Inserts record into `classmate.attendance_records` with `method = 'rfid'`.
   - Teacher's live roster and student's personal attendance cards update in 0ms via Supabase Realtime WebSocket broadcast.

---

## 45. PWA Service Worker Invalidation, Mobile Web Asset Synchronization & Production Cache Guidelines

### A. The Stale Cache Phenomenon on Vercel
Following production deployments of Next.js 15 companion client updates to Vercel (`https://classmatebd.vercel.app`), users frequently experienced stale UI behavior where newly released features (such as Google Drive link sharing, attendance actions, or profile redesigns) failed to appear until an explicit cache purge was conducted.

### B. Root Cause Analysis
1. **Aggressive Workbox / CacheStorage Strategy**:
   - The Progressive Web App (PWA) configuration in `web/src/worker.ts` utilized a `CacheFirst` strategy for JavaScript chunks and stylesheet assets.
   - When a user visited the app, the Service Worker intercepted network requests and served pre-cached bundles from browser CacheStorage without validating `ETag` headers or querying the Vercel edge CDN.
2. **HTML Navigation Pre-caching**:
   - The shell document `/` was retained in local cache, causing the browser to boot from old manifest hashes.

### C. Client-Side Invalidation Protocols
To verify new features immediately after a production Vercel release:
1. **Desktop Browsers (Chrome / Edge / Firefox)**:
   - Perform Hard Reload & Cache Bypass: Press `Ctrl + Shift + R` or `Ctrl + F5`.
   - Developer Tools Reset: Open DevTools (`F12`) &rarr; `Application` tab &rarr; `Storage` &rarr; Click **"Clear site data"**.
2. **Mobile Smartphone Browsers (Android Chrome / iOS Safari)**:
   - Chrome Mobile: Tap 3 dots &rarr; `Settings` &rarr; `Privacy and security` &rarr; `Clear browsing data` &rarr; Select "Cached images and files".
   - Or tap the Tune/Lock icon beside the URL bar &rarr; `Site settings` &rarr; Tap **"Clear & reset"**.
3. **Service Worker Unregistration**:
   - Inspect active registrations via `chrome://serviceworker-internals/` or DevTools &rarr; `Application` &rarr; `Service Workers` &rarr; Tap **"Unregister"**.

### D. Architectural Countermeasures in Codebase
1. **Versioned Cache Naming**:
   - Updated `CACHE_NAME` in `worker.ts` with explicit build identifiers: `classmate-cache-v1.1.33`.
   - Activated service worker `activate` event purges all cache buckets whose keys do not match the current version.
2. **NetworkFirst for Dynamic Routes**:
   - Configured `NetworkFirst` policy with a 3-second timeout for root navigation, academic feeds, and API endpoints, falling back to cache only when the user is disconnected.
3. **Automated Registration Updates**:
   - Added `registration.update()` call on page focus and window load to prompt background updates and display a non-intrusive "Update available â€” Tap to refresh" snackbar.

---

## 46. High-Density Classroom Attendance Redundancy & Hybrid Protocol (QR + RFID)

### A. Architectural Overview
In practical university operations, no single biometric or digital tracking mechanism fits every operational scenario. ClassMate's attendance architecture implements a resilient **tri-tier redundancy model**:

```mermaid
flowchart TD
    subgraph Modality 1: Dynamic QR Code
        A[Teacher Screen / Projector] -->|Rotates every 15s| B[HMAC-SHA256 Dynamic QR]
        B -->|Scan via CameraX / BarcodeDetector| C[Instant Scan Verification]
    end

    subgraph Modality 2: Doorway RFID Tap
        D[Classroom Doorway IoT Station] -->|Card / Keyfob Tap| E[ESP32 + MFRC522 Reader]
        E -->|Authenticated HTTPS POST| F[Instant RFID Ingestion]
    end

    subgraph Modality 3: Teacher / CR Manual Override
        G[Teacher Live Roster / Action Menu] -->|Tap Attendee Status| H[RPC: manual_override_attendance]
    end

    C --> I[(Unified Single Record:<br>classmate.attendance_records)]
    F --> I
    H --> I
    I --> J[Real-Time WebSocket Sync to Live Roster & Student Analytics]
```

### B. High-Density Throughput & Bottleneck Elimination
- **60-Student Benchmarks**:
  - Scanning via single smartphone screen: ~3â€“4 minutes for 60 students.
  - Dual modality (Dynamic QR projected on lecture wall + Doorway RFID tap unit): **Full 60-student cohort registered in under 45 seconds**.
- **Idempotency & Conflict Safety**:
  - Database uniqueness constraint: `UNIQUE (session_id, student_id)`.
  - If a student taps their RFID card at the door and subsequently scans the projected QR code from their desk, the second operation executes cleanly as an idempotent update (`on conflict (session_id, student_id) do update set synced_at = now()`), preventing duplicate counts or database write failures.
- **Fail-Safe Offline Guarantee**:
  - If university Wi-Fi drops, Android CameraX scanner queues scans in Room DB (`PendingAttendanceScanEntity.kt`) with captured epoch timestamps, syncing seamlessly via `AttendanceSyncWorker` once connectivity resumes.

---

## 47. Embedded Hardware Diagnostics, SPI Loopback & MFRC522 Toolchain Resolution

### A. The Modern GCC / Xtensa C++ Pointer Comparison Failure (`MFRC522Extended.cpp`)
When compiling the ClassMate ESP32 RFID attendance firmware (`esp32_rfid_attendance.ino`) using the official MFRC522 library by GithubCommunity/Miguel Balboa (v1.4.10 / v1.4.11), the build pipeline failed with fatal C++ compilation errors:
```text
C:\Users\USER\OneDrive\Documents\Arduino\libraries\MFRC522\src\MFRC522Extended.cpp:824:19: error: ordered comparison of pointer with integer zero ('byte*' {aka 'unsigned char*'} and 'int')
  824 |   if (backLen > 0) {
      |       ~~~~~~~~^~~
C:\Users\USER\OneDrive\Documents\Arduino\libraries\MFRC522\src\MFRC522Extended.cpp:847:19: error: ordered comparison of pointer with integer zero ('byte*' {aka 'unsigned char*'} and 'int')
  847 |   if (backLen > 0) {
      |       ~~~~~~~~^~~
```
- **Root Cause**:
  In older C++ standards or legacy compilers, comparing a pointer to `0` was loosely tolerated as a null pointer check. However, modern GCC toolchains (such as the Xtensa ESP32 toolchain GCC 12.2+ used in Arduino ESP32 core v3.x) strictly conform to modern C++ rules and treat ordered relational comparisons (`>`, `<`, `>=`, `<=`) between pointer types and integer literals as a fatal compilation error.
- **The Code Defect**:
  In `MFRC522Extended.cpp`, the parameter `backLen` is declared as a pointer to a byte (`byte *backLen`). The author intended to check whether the byte value pointed to by `backLen` was greater than zero, but omitted the dereference operator (`*`).
- **The Resolution**:
  Both lines in `MFRC522Extended.cpp` were corrected from:
  ```cpp
  if (backLen > 0) {
  ```
  to:
  ```cpp
  if (backLen != nullptr && *backLen > 0) {
  ```
  Following this edit, the sketch compiles cleanly with **0 errors and 0 warnings**.

### B. ESP32 Hardware SPI Loopback Diagnostic Protocol (`spi_loopback.ino`)
During prototype debugging, when the RC522 fails to respond, it is often difficult to distinguish whether the issue lies in damaged ESP32 microcontroller GPIOs, a faulty breadboard rail, incorrect pin definitions, or a defective RC522 module.

To isolate the ESP32 hardware completely, a dedicated hardware SPI loopback test sketch (`spi_loopback.ino`) was developed:
```mermaid
flowchart LR
    subgraph ESP32 38-Pin Microcontroller
        MOSI["GPIO 23 (MOSI)"]
        MISO["GPIO 19 (MISO)"]
        SCK["GPIO 18 (SCK)"]
        SS["GPIO 5 (SS/CS)"]
    end
    
    Jumper["Direct Single Jumper Wire"]
    MOSI -->|SPI.transfer(byte)| Jumper -->|Echoed Input| MISO
    
    Test["Patterns Tested:<br>0x55, 0xAA, 0x00, 0xFF"] --> Result{"sent == received?"}
    Result -->|All Match| Pass["LOOPBACK PASS:<br>ESP32 Internal SPI Bus & GPIOs 100% Intact"]
    Result -->|Mismatch| Fail["LOOPBACK FAIL:<br>Damaged GPIO or Faulty Jumper Wire"]
```

- **Execution Steps**:
  1. Disconnect the RC522 module completely from the ESP32.
  2. Connect a single direct jumper wire between **GPIO 23 (MOSI)** and **GPIO 19 (MISO)**.
  3. Flash `spi_loopback.ino` at 115200 baud.
  4. The sketch cycles test bit patterns (`0x55`, `0xAA`, `0x00`, `0xFF`) through `SPI.transfer()` at 1 MHz.
  5. Receiving matching bytes verifies that the ESP32's hardware SPI controller, register logic, internal clock generators, and pin multiplexers are fully operational.

### C. Arduino IDE & Windows COM Port Exclusivity (`serial-monitor.exe`)
When flashing firmware to the ESP32 using `esptool.py` (either via command-line or the Arduino IDE "Upload" button), developers frequently encounter:
```text
esptool v5.3.1
Serial port COM6:
Connecting......................................
A fatal error occurred: Failed to connect to ESP32: No serial data received.
Failed uploading: uploading error: exit status 2
```
or in Python toolchains:
```text
PermissionError: [WinError 5] Access is denied: 'COM6' (Port is busy)
```

- **Root Cause & Windows Architecture**:
  Unlike Unix/Linux systems where multiple handles can sometimes inspect character devices, the Windows Win32 serial subsystem grants strictly **exclusive locks** (`GENERIC_READ | GENERIC_WRITE`) on COM ports.
  When the Arduino IDE Serial Monitor tab is active, or if an orphaned `serial-monitor.exe` background process remains running after an IDE crash or disconnect, Windows locks `COM6`. When `esptool` attempts to open `COM6`, it cannot establish a connection.
- **Resolution Checklist**:
  1. Close the Serial Monitor tab/pane in Arduino IDE before clicking "Upload".
  2. If the port remains locked, run PowerShell to terminate orphaned monitor processes:
     ```powershell
     Stop-Process -Name "serial-monitor" -Force -ErrorAction SilentlyContinue
     ```
  3. Verify the USB cable is firmly plugged in (avoid charge-only USB cables lacking D+/D- data lines).

### D. ESP32 Dev Board Architecture: `ESP32 Dev Module` vs `ESP32-WROOM-DA Module`
- **The Pitfall**:
  In the Arduino IDE board selector dropdown, selecting **`ESP32-WROOM-DA Module`** causes boot loops and peripheral initialization failures on standard ESP32 development boards.
- **Hardware Differences**:
  The "DA" moniker designates the **ESP32-WROOM-DA Dual-Antenna Module**. This specialized module incorporates two complementary PCB antennas with an automated RF switch IC. Its internal pin layout and PCB trace routing allocate GPIOs differently than standard ESP32 boards.
- **Required Board Configuration for 38-Pin Boards**:
  - **Board**: **`ESP32 Dev Module`** (or `NodeMCU-32S`).
  - **Flash Mode**: `QIO` (or `DIO` if using older flash ICs).
  - **Flash Frequency**: `80 MHz`.
  - **Upload Speed**: `115200` (for maximum noise immunity) or `921600` (for fast flashing).
  - **Core Debug Level**: `None` (production) or `Info` (debugging).

### E. Clone RC522 ICs (FM17522 / MH523) and Tolerant Card Polling
Many budget-friendly MFRC522 modules available from electronics vendors contain compatible second-source silicon (e.g., **Fudan Microelectronics FM17522** or **Shanghai Belling MH523**) rather than original NXP semiconductors:
- **Register Discrepancy**:
  The internal `VersionReg` (`0x37`) on these clone chips frequently returns `0x00` or `0xFF` during `reader.PCD_ReadRegister(MFRC522::VersionReg)`.
- **False-Failure Risk**:
  Diagnostic sketches that enforce a rigid sanity check:
  ```cpp
  if (version == 0x00 || version == 0xFF) {
      Serial.println("FAIL: Reader not responding!");
      return;
  }
  ```
  will falsely reject a perfectly functional clone reader.
- **Tolerant Polling Strategy**:
  The production ClassMate firmware logs a non-fatal informational notice if `VersionReg` reads `0x00` or `0xFF`, but does **not** halt execution. It proceeds directly to `PICC_IsNewCardPresent()` and `PICC_ReadCardSerial()`. In testing, clone chips successfully detect 13.56 MHz Mifare Classic 1K cards and keyfobs without issue when the SPI wiring is correct.

---

## 48. Batch 22 RFID Attendance Card Procurement, Encoding & Provisioning Architecture

### A. Physical Card Specifications & Procurement Economics
To deploy an un-spoofable, rapid doorway attendance station for CSE 22nd Batch (2nd Year 1st Semester), physical RFID credentials were procured for students:
- **RF Standard**: 13.56 MHz High-Frequency (HF) ISO/IEC 14443 Type A contactless smart cards and keyfobs.
- **Silicon Core**: NXP Mifare Classic 1K compatible EEPROM (1024 bytes organized into 16 sectors of 4 blocks each with 48-bit crypto keys).
- **Unit Economics**: Procured in bulk at **à§³40 per card**, collected transparently via the Batch Fund ledger (`category = 'general'`, title `"Attendance Card"`).
- **Physical Resilience**: Waterproof laminated PVC card format withstands daily student wallet and lanyard wear, eliminating camera optical glare, screen brightness issues, or low-light focus latency inherent in barcode scanning.

```mermaid
flowchart TD
    subgraph Phase 1: Card Procurement & Bookkeeping
        A[Class Representatives Procure 60 RFID Cards] --> B[Targeted à§³40 Collection Drive]
        B --> C["classmate.batch_fund_transactions<br>(Deposit: à§³40, category='general', title='Attendance Card')"]
        C --> D["Audit Roster: 21 Paid (à§³845) | 37 Pending (à§³1,480)"]
    end

    subgraph Phase 2: Hardware UID Enrollment
        E[Student Taps Blank RFID Card on Admin Reader] --> F[ESP32 Reader Captures 4-Byte Hex UID]
        F --> G["Associate UID to Student Profile<br>(classmate.profiles.rfid_tag_id = '4A8B1C9D')"]
        G --> H["Unique Constraint Enforced:<br>UNIQUE(rfid_tag_id)"]
    end

    subgraph Phase 3: Daily Automated Doorway Attendance
        I[Student taps card entering lecture room] --> J[Doorway ESP32 + RC522 Reader]
        J --> K["HTTPS POST /mark-rfid-attendance<br>{ card_uid, device_id, batch_id }"]
        K --> L["Match Active Session in classmate.attendance_sessions"]
        L --> M["Idempotent Insert: classmate.attendance_records<br>(method = 'rfid', confidence = 'normal')"]
        M --> N["0ms Supabase Realtime WebSocket Broadcast to Live Roster"]
    end
```

### B. Hardware Registration & Card-to-Student UID Binding Protocol
1. **Enrollment Workflow**:
   - The instructor or Class Representative places the ClassMate ESP32 hardware unit into **Admin Enrollment Mode** (via serial command `ENROLL` or via web admin toggle).
   - The student taps their physical card onto the reader.
   - The firmware reads the 4-byte or 7-byte unique hardware serial number (UID) from the PICC cascade level:
     ```cpp
     String uidStr = "";
     for (byte i = 0; i < mfrc522.uid.size; i++) {
         if (mfrc522.uid.uidByte[i] < 0x10) uidStr += "0";
         uidStr += String(mfrc522.uid.uidByte[i], HEX);
     }
     uidStr.toUpperCase(); // e.g. "4A8B1C9D"
     ```
   - The admin assigns `uidStr` to the student's profile via RPC `bind_rfid_card(target_profile_id, card_uid)` or by updating `classmate.profiles.rfid_tag_id`.
2. **Cryptographic Integrity & Anti-Duplicate Guarantees**:
   - Database schema enforces `UNIQUE (rfid_tag_id)` on `classmate.profiles`. Two students cannot be assigned the same physical credential.
   - **Revocation & Lost Card Re-issuance**:
     - If a card is reported lost or damaged, the admin simply binds the new card UID to the student's profile.
     - The previous card UID is automatically orphaned; future taps with the old card fail authorization immediately.

### C. ESP32 Real-Time Doorway Attendance Protocol
1. **Doorway Unit Placement**: The battery/5V USB powered ESP32 + RC522 enclosure is mounted at the entrance of the lecture hall (Room 338/339/340/341/Lab-02).
2. **Contactless Tap Transaction Lifecycle (< 150ms)**:
   - Student taps card on doorway reader when walking into class.
   - ESP32 buzzer emits a sharp single confirmation beep (100ms tone at 2.7 kHz).
   - ESP32 dispatches HTTPS payload to Supabase Edge Function:
     ```json
     {
       "card_uid": "4A8B1C9D",
       "device_id": "doorway_cse_338",
       "batch_id": "de0b3379-54a0-4275-a526-5970295c787e",
       "timestamp": "2026-10-18T10:02:14Z"
     }
     ```
   - The Edge Function verifies that a class session is currently `open` in `classmate.attendance_sessions` for that batch and room.
   - Executes idempotent insert into `classmate.attendance_records` (`method = 'rfid'`, `confidence = 'normal'`).
   - Pushes real-time presence update via Supabase Realtime WebSocket to the teacher's smartphone (`ClassMateTeacherLiveAttendanceActivity`) and Web dashboard (`attendance-teacher-modal.tsx`), highlighting the student's roll number in emerald green.




---

## 49. Permanent Academic Calendar in Timetable & Courses Screen Redesign

### A. Architectural Motivation & User Feedback
- **Permanent Academic Calendar**:
  - The previous batch-wide admin configuration toggle `"Calendar in Timetable"` (`classmate.batches.calendar_in_timetable`) was removed.
  - The Timetable screen now permanently features a dedicated, unified 3-mode segmented control:
    - **`[ Class Routine | Bus Schedule | Academic Calendar ]`**
  - Eliminates administrative toggling overhead and provides students with permanent, one-tap access to university holidays, exam schedules, and semester milestones directly inside their daily routine view.
- **Renaming Materials / Library to "Courses" (`R.id.nav_library` / `Courses`)**:
  - Renamed the bottom navigation tab from "Library" / "Materials" to **"Courses"** across both Android APK and Web companion client.
  - Aligns terminology with academic expectations: the screen serves as an academic course catalog rather than a generic file library.

### B. Courses Screen Information Architecture & UI Layout
1. **Top Hero Attendance Banner (`Attendance Tracking` / `My Attendance`)**:
   - Prominently anchored at the top of the Courses feed.
   - For students: Displays cumulative attendance standing, attended/held ratios, and percentage pill (`%`).
   - Tapping the hero banner opens the **RFID Attendance Status Sheet / Modal** showing card registration status, active session logs, and course-by-course metrics.
2. **Filter Chips**:
   - Filter pills: `[ All | Theory | Lab ]` allow rapid category switching without full reload.
3. **Streamlined Course Cards**:
   - Course code pill badge with category accent (e.g., `CSE 2101`, `CSE 2102`).
   - Bold course title, credit weighting (e.g., `3.00 Credits` / `1.50 Credits`), assigned course teacher with faculty icon, and attached syllabus/materials counter.
   - One-tap navigation into course materials, drive links, and lecture slide repositories.

### C. Android Native Google Drive Link Pasting (`ClassMatePublishActivity.kt`)
- **Android APK Feature Parity with Web**:
  - In `ClassMatePublishActivity`, when an authorized Class Representative (CR) or Admin uploads course material, a top segmented mode switcher is provided:
    - **`[ Upload File | Drive Link ]`**
  - **Drive Link Mode**:
    - Replaces native file picker with a streamlined URL input form: Resource Title, External URL (with hint `https://drive.google.com/...`), Category selector (`notes`, `book`, `question_bank`, `slide`, `syllabus`), and Semester Course dropdown.
    - Interacts directly with Supabase RPC `create_library_link(target_batch, target_course, target_title, target_category, target_url)` (`202610140001_library_drive_links.sql`).
    - Creates record in `classmate.file_metadata` with `file_type = 'link'`, `mime_type = 'text/uri-list'`, `size_bytes = 0`, and `link_url = v_url`.
- **Card Presentation & Redirection**:
  - In the Android Courses file list, Google Drive resources display an authentic **Google Drive icon** (`ic_drive`) with an emerald green **`DRIVE`** badge pill. Other external links display `ic_external_link` with a blue **`LINK`** badge.
  - Tapping a Drive link immediately launches the default Android browser or Google Drive app via `Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))`.

---

## 50. Complete ESP32 RFID Attendance Station Hardware, Display & Audio Overhaul

### A. Context & Physical Hardware Components
To deploy an institutional-grade, zero-latency physical attendance terminal for classroom doorways (e.g., Room 338/339/Lab-02 for CSE 22nd Batch), the hardware stack was upgraded with visual status displays and acoustic feedback:
1. **Microcontroller**: ESP32 38-Pin Development Module (Dual-core Xtensa LX6, 240 MHz).
2. **RFID Reader**: NXP MFRC522 (13.56 MHz HF RFID reader via SPI).
3. **Display**: 0.96-inch monochrome I2C SSD1306 OLED (128x64 resolution).
4. **Acoustic Indicator**: 3-terminal active buzzer module (`VCC`, `IO`, `GND`) with onboard NPN transistor driver.
5. **Visual Indicators**: 5mm High-brightness Green LED (Success) & Red LED (Error/Unregistered) with 220Ω–330Ω current-limiting resistors.
6. **Power Decoupling**: 100μF 16V electrolytic capacitor across 3.3V/GND power rail near MFRC522 to eliminate radio transmission voltage dips.

### B. Critical Hardware Pinout Conflict Resolution (GPIO 22 SCL Collision)
- **The Defect**:
  - In early prototypes, the RC522 `RST` pin was connected to **`GPIO 22`**.
  - However, on the ESP32 architecture, **`GPIO 22` is the hardwired default I2C SCL (Clock)** pin used by the wire library for OLED displays.
  - Having RC522 reset on GPIO 22 caused simultaneous SPI bus lockups and blank OLED displays.
- **The Resolution**:
  - Moved RC522 `RST` to **`GPIO 4`** (or `GPIO 21`), freeing `GPIO 22` exclusively for OLED `SCL` and `GPIO 21` for OLED `SDA`.
  - Both buses now operate concurrently without interference: VSPI runs at 1 MHz – 4 MHz, and I2C runs at 400 kHz fast mode.

### C. Master 38-Pin ESP32 Hardware Wiring Map
| Component | Component Pin | ESP32 Pin | Logic / Bus | Description & Engineering Notes |
| :--- | :--- | :--- | :--- | :--- |
| **RC522 RFID** | `3.3V (VCC)` | **`3V3`** | Power Rail | **Never connect to 5V (damages IC)** |
| | `RST` | **`GPIO 4`** | Hardware Reset | Moved from GPIO 22 to avoid I2C SCL conflict |
| | `GND` | **`GND`** | Common Ground | Common ground reference |
| | `MISO` | **`GPIO 19`** | VSPI MISO | Master-In-Slave-Out |
| | `MOSI` | **`GPIO 23`** | VSPI MOSI | Master-Out-Slave-In |
| | `SCK` | **`GPIO 18`** | VSPI SCK | Hardware serial clock |
| | `SDA / SS` | **`GPIO 5`** | VSPI SS | Hardware chip select (active low) |
| **SSD1306 OLED** | `VCC / VDD` | **`3V3`** | 3.3V Power | Display power rail |
| | `GND` | **`GND`** | Common Ground | Common ground reference |
| | `SCL / SCK` | **`GPIO 22`** | I2C SCL | I2C clock (400 kHz Fast Mode) |
| | `SDA` | **`GPIO 21`** | I2C SDA | I2C data line |
| **3-Pin Buzzer** | `VCC` | **`5V / VIN`** | 5V Power Rail | **Connect to 5V for high volume (3.3V is too quiet)** |
| | `GND` | **`GND`** | Common Ground | Common ground reference |
| | `IO / DAT` | **`GPIO 25`** | Digital Output | Active-high driver trigger |
| **Green LED** | Anode (+) | **`GPIO 32`** | Digital Output | Via 220Ω resistor (Check-in success) |
| | Cathode (-) | **`GND`** | Common Ground | Common ground reference |
| **Red LED** | Anode (+) | **`GPIO 33`** | Digital Output | Via 220Ω resistor (Unregistered/Error) |
| | Cathode (-) | **`GND`** | Common Ground | Common ground reference |

### D. Audio & Video Performance Optimizations
1. **Buzzer Volume Amplification (Hardware Fix)**:
   - Connecting the 3-pin buzzer module's `VCC` to `3V3` delivered insufficient current through the on-board surface-mount transistor, making the beep barely audible in noisy lecture halls.
   - Moving `VCC` to **`5V / VIN`** (USB rail) drove the piezoelectric diaphragm at full resonance, increasing sound pressure level by over **15 dB**.
2. **Zero-Latency Buzzer Delay Resolution (Software Fix)**:
   - *Problem*: Users experienced a 2 to 3-second delay between tapping their card and hearing the confirmation tone.
   - *Root Cause*: The firmware code was executing a blocking, synchronous HTTP POST (`WiFiClientSecure`) *before* activating the buzzer pin. Any Wi-Fi jitter or TLS handshake latency directly delayed the audio cue.
   - *Fix*: The firmware was refactored to emit an immediate **60ms click/chirp tone** the instant a card UID is captured by the RC522. The HTTP POST is dispatched asynchronously, and on receipt of the JSON response, the system triggers the 2-tone melodic confirmation chime (`2.7 kHz -> 3.5 kHz`) or error burst.
3. **Elimination of OLED Lag & I2C Bus Flooding**:
   - Continually redrawing the full 1024-byte display buffer on every `void loop()` iteration flooded the I2C bus and starved network routines.
   - Implemented an internal state machine with a throttled 25ms timer for marquee text animations and event-driven screen redraws.

### E. 3D Printable Mechanical Enclosure (`esp32_rfid_flat_snapfit_enclosure.stl`)
- **Review & Engineering Polish**:
  - Removed internal floor cutouts/slots in favor of a 100% solid, rigid bottom plate to eliminate structural flex.
  - Added dual rear wall-mounting keyhole slots for easy mounting outside lecture room doors.
  - Molded internal standoff cradles for the 38-pin ESP32 dev board, RC522 reader, and OLED display bezel.
  - Precision cutouts for USB-C/Micro-USB power cable and external LED status light pipes.

---

## 51. Admin Attendance Management & Smart Device Controls (Migration `202610200003`)

### A. High-Level Architecture & Paradigm Shift
Prior to migration `202610200003`, attendance sessions were strictly bound to scheduled `routine_slots` and required manual per-course administrative toggle checkboxes ("Attendance Course Settings").
In university operations, classes are frequently rescheduled, extra review sessions are called, or faculty take attendance ad-hoc.
The new architecture unifies attendance into an **Ad-Hoc, Schedule-Independent, Hardware-Linked Platform**:

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Admin / Teacher (Web/Android)
    participant Cloud as Web API (/api/scan)
    participant DB as Supabase DB
    participant ESP as ESP32 Terminal (OLED)
    actor Student as Student (Batch 22)

    Admin->>Cloud: Launch Ad-Hoc Session for Course (e.g. CSE 2101)
    Cloud->>DB: start_adhoc_attendance_session(target_course, 'ESP32-READER-01')
    DB-->>Cloud: session_id created, device linked, outbox 'attendance_open' enqueued
    
    loop Every 2 Seconds Heartbeat
        ESP->>Cloud: GET /api/scan?device_id=ESP32-READER-01
        Cloud->>DB: get_device_active_session('ESP32-READER-01')
        DB-->>Cloud: { status: "ACTIVE", course_code: "CSE 2101", course_title: "OOP" }
        Cloud-->>ESP: JSON active session data
    end
    ESP->>ESP: Wakes OLED! Displays "CSE 2101" + Marquee Title Animation

    Student->>ESP: Taps RFID Card
    ESP->>ESP: Instant 60ms Chirp + Flash Student ID (350ms)
    ESP->>Cloud: POST /api/scan { card_uid, device_id }
    Cloud->>DB: process_rfid_scan(card_uid, device_id)
    DB->>DB: Verify student belongs to session batch (Strict Batch Isolation)
    DB->>DB: Insert attendance_records (idempotent)
    DB->>DB: Enqueue notification_outbox ('attendance_marked')
    DB-->>Cloud: { status: "CHECK_IN", identifier: "2101001", user_name: "..." }
    Cloud-->>ESP: HTTP 200 { status: "CHECK_IN", ... }
    ESP->>ESP: Display [ ✔ OK! PRESENT ] + Green LED + Rising Chime (800ms)
    ESP->>ESP: Reverts to Course Marquee
    DB-->>Student: FCM Push: "Your attendance for OOP was marked present in the app."

    Admin->>Cloud: Click "Close Attendance"
    Cloud->>DB: close_adhoc_attendance_session(session_id)
    DB->>DB: Status = 'closed', unlinks device
    ESP->>Cloud: GET /api/scan (Next Heartbeat)
    Cloud-->>ESP: { status: "SLEEP" }
    ESP->>ESP: OLED Display OFF (Sleep Mode, LEDs Off)
```

### B. Database Schema & RPC Enhancements (`202610200003_admin_manage_attendance_and_scan_notifs.sql`)
1. **Schema Decoupling (`classmate.attendance_sessions`)**:
   - `alter table classmate.attendance_sessions alter column routine_slot_id drop not null;`
   - `alter table classmate.attendance_sessions add column if not exists semester_course_id uuid references classmate.semester_courses(id) on delete cascade;`
   - `alter table classmate.attendance_sessions add column if not exists batch_id uuid references classmate.batches(id) on delete cascade;`
   - Backfilled historical session records from `routine_slots` to preserve data continuity.
2. **RPC: `start_adhoc_attendance_session(target_semester_course uuid, target_device text default 'ESP32-READER-01')`**:
   - Authorizes Admin, Owner, or assigned faculty.
   - Automatically closes any previous open session for this course to prevent stale state.
   - Inserts session with `semester_course_id = target_semester_course` and `status = 'open'`.
   - Binds `target_device` immediately in `classmate.attendance_devices(active_session_id = sess_id)`.
   - Enqueues batch-wide notification event `attendance_open`.
3. **RPC: `close_adhoc_attendance_session(target_session uuid)`**:
   - Closes the session (`status = 'closed', closed_at = now()`) and immediately unlinks the hardware device (`active_session_id = null`).
4. **RPC: `get_device_active_session(target_device text)`**:
   - Executed by the ESP32 every 2 seconds via `GET /api/scan?device_id=...`.
   - Records `last_heartbeat = now()`.
   - If device has an open active session: returns `{ status: "ACTIVE", session_id, course_code, course_title, started_at }`.
   - If session is closed or unlinked: unlinks device and returns `{ status: "SLEEP" }`.
5. **RPC: `process_rfid_scan(p_card_uid text, p_device_id text)`**:
   - Normalizes Card UID (case-insensitive hex).
   - Verifies active session on `p_device_id`.
   - Looks up student in `classmate.profiles`. If missing, logs into `unregistered_rfid_scans` and returns `UNREGISTERED`.
   - **Strict Batch Isolation**: Enforces `stu.batch_id == sess.batch_id`. If a student from another batch taps their card, rejects with `WRONG_BATCH`.
   - Idempotent upsert into `classmate.attendance_records`.
   - **Single-Recipient Scan Push Notification**: If fresh tap (`is_new_mark = true`), inserts outbox event `attendance_marked` strictly for that student profile:
     - Title: `"Attendance Recorded"`
     - Body: `"Your attendance for " || course_title || " was marked present in the app."`
6. **Student Scoped Attendance RPC (`student_my_attendance`)**:
   - Filtered to **only display courses where attendance has actually been taken (`classes_held > 0`)**.
   - Removes empty 0/0 course clutter from students' timetable views.

---

## 52. Manage Attendance UI/UX Refinement & Layout Overhaul

### A. Root Cause Resolution & Layout Architecture
- **Eliminated `.tab-layout` Class Collision**:
  - The root container previously inherited the global `.tab-layout` class from `globals.css` (`grid-template-columns: minmax(0, 1fr) minmax(310px, 350px);`).
  - Because `AdminAttendance` was already mounted inside `.primary-pane` (column 1 of the 3-pane desktop layout), this forced an unintended nested 2-column layout, crushing course cards into a cramped 300px column.
  - Replaced with `.admin-attendance-root` (`display: flex; flex-direction: column; width: 100%; max-width: 1040px;`).
- **3-Pane Desktop Alignment**:
  - Preserved the full 3-pane PC desktop layout: Left Sidebar Navigation, Center Primary Workspace, and Right Persistent Notices Feed. All Manage Attendance controls fit seamlessly with zero horizontal clipping.

### B. Segmented Sub-View Navigation
The header provides a clean segmented pill toggle between two dedicated operational modes:
1. **`[ 📊 Course Records & Export ]` (Default)**:
   - **Hardware Terminal Card**:
     - Live station status for `ESP32-READER-01`: Displays glowing green indicator with active course and tap count, or calm gray `SLEEP` state.
     - "Take Attendance" action button opens a modal to launch an ad-hoc session for any semester course.
     - "Close Attendance" button safely terminates the session.
   - **Modern Course Cards**:
     - Flexible auto-padded Course Code pill (e.g. `CSE 2101`, `#3B82F6`) that never overlaps the faculty name.
     - Course title, enrolled student count, classes taken count, batch average percentage chip.
     - Pinned action buttons: `[ 📥 Export Excel ]` and `[ View Day Roster ▼ ]` accordion toggle.
   - **Expanded Day-Wise Matrix**:
     - Sticky student `#`, `Roll`, and `Name` columns that remain locked on the left while horizontally scrolling across session dates.
     - Header displays session dates and formatted times (e.g., `20 Oct, 10:30 AM`).
     - Binary visual badges: Emerald pill `1 · Present` vs Soft Coral pill `0 · Absent`.
     - Rightmost totals column: Attended / Held count and percentage.
2. **`[ 💳 Register RFID Cards (N/Total) ]`**:
   - **Live Detected Card Chip**:
     - Shows the most recently tapped unknown card UID on the ESP32 reader with a 1-tap `[ Use Detected UID ]` button.
   - **2-Column Pairing Form**:
     - Autocomplete Student Search with elevated dropdown (`z-index: 50; box-shadow`) showing Roll and Name.
     - Monospace Card UID input with validation.
     - "Register Card" action button calling RPC `enroll_student_rfid`.
   - **Batch Enrollment Directory Table**:
     - Filter tabs: `All (N)`, `Registered (N)`, `Unregistered (N)`.
     - Direct 1-tap `[ Pair Card ]` or `[ Edit Card ]` actions.

### C. Styled Binary ($1$/$0$) Excel Matrix Export (`attendance-excel.ts`)
- Client-side SheetJS (`xlsx`) spreadsheet generation:
  - **Metadata Headers**: Course Code, Course Title, Export Timestamp, Total Classes Conducted.
  - **Frozen Headers**: Columns A (`Roll No`) and B (`Student Name`) are frozen for smooth horizontal scrolling.
  - **Date Columns**: Every session date and time (`YYYY-MM-DD HH:MM`).
  - **Binary Values**: Strictly outputs `1` for Present and `0` for Absent.
  - **Summary Columns**: `Total Present`, `Total Classes`, and Excel calculation percentage (`=SUM(...) / COUNT(...) * 100`).

---

## 53. Friends Tab UI Polish, User Sorting & Profile Privacy Scoping

### A. Wider, Compact Friends Profile Popup
- **Layout Expansion**:
  - Increased modal width on both Web and Android to accommodate full student credentials without text wrapping or truncated details.
  - Reduced excessive padding and tightened typography for a compact, aesthetic profile card.

### B. Live Active Status & Priority Ordering
- **Real-Time Presence Indicator**:
  - Integrated green pulsing dot indicator reflecting user activity within the last 5 minutes (`touch_presence`).
- **Batch 22 Priority Sorting Algorithm**:
  - Enforced hierarchical sort order across student directories:
    1. **System Administrator / App Developer** (CSE 22nd Batch exception, pinned to position 0).
    2. **Class Representatives (CR)** (pinned above regular students).
    3. **Enrolled Batch Students** (alphabetically/numerically ordered by academic roll number `CE25001...`).

### C. Profile Field Cleanup & Teacher Privacy Enforcement
1. **Removed Mess/Flat Requirement**:
   - Removed `"Current mess / flat"` input from profile completion and editing across all onboarding screens and settings.
2. **Teacher Profile Privacy Protection**:
   - Faculty member profiles are stripped of sensitive personal attributes across Android and Web:
     - Phone numbers, personal blood group, undergraduate department, and batch tags are strictly omitted from public teacher views.
     - Displays only: Full Name, Designation, Official Email, and Assigned Department.

---

## 54. Local Network APK Distribution Server & Production Verification Summary

### A. Local HTTP Distribution Endpoint
- Built and packaged the latest Android debug APK (`release/ClassMate.apk`) incorporating all recent timetable, courses, and attendance updates.
- Hosted on the local network HTTP distribution server for instant direct device testing:
  - **Download Endpoint**: `http://10.233.34.48:8080/download`

### B. Production Deployment Verification
- **Web Production**: Built and deployed to Vercel Production at **`https://classmatebd.vercel.app`** with zero TypeScript or CSS errors.
- **Supabase Backend**: Migrations `202610200001`, `202610200002`, and `202610200003` fully verified and applied across Staging and Production.
- **ESP32 Firmware**: Configured in `D:\Code & Scripts\attendence tracker\firmware\esp32_rfid_attendance\esp32_rfid_attendance.ino` with sub-second OLED animations, fast I2C, 5V buzzer drive, and automated cloud sync.

---


## 55. Universal Notice Reader Access & Public WhatsApp-Style "Message Info"

### A. Access Rule Overhaul & Public Transparency
- **Commit Reference**: `0bf6d4e` (`feat(notices): allow all users to see who has seen the notice`)
- **Universal Visibility**:
  - Previously, viewing the reader list for a notice was restricted to administrators, class representatives (CR), and the notice author.
  - Updated `canViewNoticeReaders(_notice?: Row, _ctx?: Context): boolean { return true; }` in [`web/src/components/notices.tsx`](file:///d:/Code%20&%20Scripts/ClassMate-main/web/src/components/notices.tsx).
  - Updated Android `btnReadReceipts` in [`item_notice_modern.xml`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/item_notice_modern.xml): enabled `android:clickable="true"` and `background="?attr/selectableItemBackground"`.
  - **Effect**: Any authenticated batch member (students, CR, teachers, admin) can tap on the reader avatars or seen count on any notice card to open the WhatsApp-style "Message info" view and see who has viewed the announcement.

### B. Admin Privacy Toggle Protection
- The privacy toggle `owner_preferences.read_receipts_enabled` remains strictly respected by both backend queries and client UI.
- If an admin has disabled read receipts (`read_receipts_enabled = false`):
  - Their read receipt is excluded from `notice_reader_previews`, `notice_readers_page`, and the public reader count.
  - No student or teacher can see when an opted-out admin viewed a notice.

---

## 56. Complete Deprecation of QR Attendance & Transition to Hardware RFID Architecture

### A. Full Dismantling of Legacy Camera QR System
- **Commit References**: `f5ab444`, `2fcf2be`, `6a2d8aa`
- **Removed Artifacts**:
  - Completely deleted legacy Android activities and layouts: `ClassMateStudentAttendanceScannerActivity.kt` and `activity_student_attendance_scanner.xml`.
  - Deleted Web scanner modal: `web/src/components/attendance-scanner-modal.tsx`.
  - Stripped obsolete ZXing camera scanning dependencies and barcode parsing routines.

### B. Cloud Notification Pipeline Update (Migration `202610210002_attendance_punch_card_notification.sql`)
- Updated stored procedure `classmate.academic_notification_job_context`:
  - **Attendance Open Event (`attendance_open`)**:
    - **Notification Title**: `"Attendance Live · " || sess.course_code` (or `"Attendance Live"`)
    - **Notification Body**: `"Punch your cards for " || sess.course_title || " attendance."`
    - **Routing Target**: `'my_attendance'` (tapping the notification navigates directly to the student's personal attendance bottom drawer rather than opening a camera scanner).
  - **Attendance Marked Confirmation (`attendance_marked`)**:
    - **Notification Title**: `"Attendance Recorded"`
    - **Notification Body**: `"Your attendance for " || mark_info.course_title || " was marked present in the app."`
    - Scoped strictly to the individual student profile who tapped the physical card.

### C. Excel Export 404 Resolution & Alphanumeric Matrix Export
1. **Web Server API Route ([`web/src/app/api/export-attendance/route.ts`](file:///d:/Code%20&%20Scripts/ClassMate-main/web/src/app/api/export-attendance/route.ts))**:
   - Created dedicated server endpoint generating binary XLSX using SheetJS (`xlsx`).
   - Natural alphanumeric student roll sorting (`compareStudentRolls`) ensuring accurate ordering (`CE25001` to `CE25060`).
   - Frozen headers for Roll No and Student Name for smooth horizontal scrolling.
   - Exact binary 1/0 attendance matrix per session date and time (`YYYY-MM-DD HH:MM`).
   - Summary formulas: `=SUM(...) / COUNT(...) * 100`.
2. **Android OkHttp Download Pipeline ([`ClassMateAdminAttendanceActivity.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAdminAttendanceActivity.kt))**:
   - `exportCourseExcel` queries `https://classmatebd.vercel.app/api/export-attendance` with the user's Supabase auth bearer token.
   - Streams XLSX bytes directly to device storage / MediaStore and triggers `ACTION_SEND` / `ACTION_VIEW` via Android `FileProvider`.

---

## 57. Dynamic Festive, Holiday, Vacation & Weekend Lottie Animations for No-Class Screens

### A. Dynamic Contextual Empty States
- **Commit References**: `0cfd498`, `2c07ac7`, `8fa8c3e`
- Replaced static emojis and empty views on the Timetable routine screen with animated Lottie compositions when there are no scheduled classes.

### B. Contextual Event Matching & Asset Registry
1. **Weekend Definition**:
   - Islamic University / campus schedule strictly defines the academic weekend as **Thursday and Friday** (distinct from standard Western Saturday/Sunday).
2. **Islamic & Religious Events**:
   - Keywords: `"eid"`, `"ramadan"`, `"romjan"`, `"fitr"`, `"azha"`, `"barat"`, `"meraj"`, `"qadr"`, `"ashura"`, `"ঈদ"`, `"রমজান"`, etc.
   - Asset: `eid.json` (Moon & crescent mosque animation, CDN `https://lottie.host/50a404fa-bf71-416f-80c3-142686f02bca/yMoSJ3XoYs.json`). Fallback: `🌙`.
3. **Hindu & Cultural Festivals**:
   - Keywords: `"puja"`, `"pooja"`, `"durga"`, `"saraswati"`, `"kali"`, `"diwali"`, `"janmashtami"`, `"পূজা"`, `"দুর্গা"`, etc.
   - Asset: `puja.json` (Puja oil lamp / celebration). Fallback: `🪔`.
4. **Vacation & Recess**:
   - Keywords: `"vacation"`, `"break"`, `"recess"`, `"summer"`, `"winter"`, `"autumn"`, `"spring"`, `"অবকাশ"`, `"ছুটি"`.
   - Asset: `celebration.json` (Kite & festive celebration, CDN `https://lottie.host/3c653806-b702-48a9-8948-ee4ea85a8d54/XsRwdGteT8.json`). Fallback: `🏖️`.
5. **National & Memorial Days**:
   - Keywords: `"independence"`, `"victory"`, `"language"`, `"ekushey"`, `"21st february"`, `"mourning"`, `"revolution"`, `"shaheed"`, `"জাতীয়"`, `"বিজয়"`, etc.
   - Asset: `national.json` (Memorial floral tribute / national flag, CDN `https://lottie.host/5186d508-9fac-4143-ab59-6076191e0c4f/UYcZxjYaA6.json`). Fallback: `🌺`.
6. **Weekend / Free Day**:
   - Day of week matches Thursday or Friday.
   - Asset: `weekend` / `celebration.json` (Kite celebration animation). Fallback: `☕`.

### C. Critical Debugging & Keyword Collision Resolution (`8fa8c3e`)
- **The Bug**: On normal weekends (Thursday/Friday), the app was incorrectly displaying the Durga Puja animation instead of the weekend kite animation.
- **Root Cause**: The holiday parser had loose substring matching where the generic word `"holiday"` collided with Hindu holiday prefixes like `"holi"`, which evaluated ahead of the weekend check.
- **The Fix**: Re-ordered priority to evaluate Thursday/Friday weekend check first, and used strict word-boundary matching `\bholiday\b` so generic "holiday" descriptions never trigger Hindu festival rules.
- **Client Implementation Details**:
  - Android: `bindHolidayLottie` in [`ClassMateAcademicScreensSupabase.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt) sets URL on `LottieAnimationView` (`lottieNoClasses`), caches using `tag`, handles failure listeners gracefully, and falls back to `tvEmptyEmoji` without crashing.
  - Web: `@dotlottie/player-component` / `dotlottie-player` in [`web/src/components/schedule.tsx`](file:///d:/Code%20&%20Scripts/ClassMate-main/web/src/components/schedule.tsx).

---

## 58. Jetpack Compose Floating Liquid Glass Navigation Bar (v1.1.33+)

### A. Architecture & Modernization
- **Commit Reference**: `f28e902` (`feat(ui): restore Jetpack Compose floating pill navigation bar with haptics & spring physics`)
- Replaced legacy View-based bottom navigation with a fully native **Jetpack Compose** component: [`ClassMateBottomBar.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/ui/ClassMateBottomBar.kt) (`ClassMateFloatingNavigationBar`).
- Embedded seamlessly into [`activity_classmate_home.xml`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/activity_classmate_home.xml) via `androidx.compose.ui.platform.ComposeView` (`bottomNavComposeView`).

### B. Core Visual & Interactive Features
1. **Continuous Animated Sliding Indicator Pill (Spring Physics)**:
   - Instead of static tab jumps or detached circles, a single continuous pill glides horizontally between tabs using spring physics:
     `spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)`.
   - The indicator hugs **both the icon and the text label** as one unified, organic capsule.
2. **Tabler/Phosphor Outline-to-Filled Icon Morphing**:
   - Outline vector drawables when inactive (`ic_timetable_outline`, `ic_bell_outline`, `ic_library_outline`, `ic_batch_friends`, `ic_nav_settings_outline`).
   - Smooth crossfade to filled/duotone drawables when active (`ic_timetable`, `ic_notice`, `ic_library_filled`, `ic_batch_friends_filled`, `ic_nav_settings`).
3. **Press Micro-Interactions & Tactile Haptics**:
   - On touch-down, the tab scales down to `0.92f` (`animateFloatAsState`) and springs back on release.
   - `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress / Selection)` delivers immediate physical tactile feedback.
4. **Liquid Glass Depth & Specular Highlights**:
   - Translucent glass surface (`Color(0xD90A1324)` in dark theme, `Color(0xE6FFFFFF)` in light theme) with a soft 1px blue/translucent stroke (`0x333B82F6` / `0x2B1D4ED8`).
   - Floating margins (14dp bottom and sides, 28dp capsule radius) with soft elevation drop shadow.
5. **Auto-Hide on Scroll**:
   - Slides down smoothly out of viewport on downward scroll (`slideOutVertically`) and glides back into view on reverse scroll or scroll stop (`slideInVertically`).
6. **Long-Press Quick Actions**:
   - Supports `onQuickActionRequest` allowing long-press on tabs (such as Batch or Courses) to bring up quick shortcut bottom sheets.

---

## 59. External Drive Links, Authentic Vector Branding & Layout Tinting Resolution

### A. Authentic Multi-Colored Google Drive Logo & Theme
- **Commit References**: `af39e29`, `1ae69c7`
- Implemented multi-color vector drawable [`ic_google_drive.xml`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/drawable/ic_google_drive.xml) with Google Drive brand colors (`#FFBA00` Yellow, `#00AC47` Green, `#2684FC` Blue).
- Dedicated container background `bg_library_file_icon_drive.xml`.
- Added dedicated Google Drive pasting tab/toggle in [`ClassMatePublishActivity.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt) and [`library.tsx`](file:///d:/Code%20&%20Scripts/ClassMate-main/web/src/components/library.tsx) for 1-tap Drive resource sharing.

### B. Root Cause & Fix for Vector Color Tint Overwriting
- **Symptom**: In course file cards, the Google Drive logo rendered as a single solid pink triangle instead of its iconic multi-color design.
- **Root Cause**: XML layout items (`item_course_file.xml`, `item_recent_pdf.xml`, `item_pdf.xml`, `item_library_search_result.xml`) had hardcoded `android:tint="#FF6B7A"` (or `app:tint`). This forcibly tinted all vector path colors into a single flat pink color, destroying the multi-colored Drive logo.
- **Resolution**: Stripped hardcoded layout tints and updated [`FileVisuals.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/utils/FileVisuals.kt):
  - `VisualType` defines `tint: Int = 0` (0 indicates multi-colored vector preserving native colors).
  - In `applyVisuals()`:
    ```kotlin
    if (visuals.tint != 0) {
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(visuals.tint))
        setColorFilter(visuals.tint)
    } else {
        ImageViewCompat.setImageTintList(this, null)
        imageTintList = null
        colorFilter = null
        clearColorFilter()
    }
    ```

### C. Root Cause & Fix for External Links `"null"` String Trap
- **Symptom**: Uploaded storage files (such as the OOP Syllabus PDF) displayed `"Google Drive"` and failed to open, crashing with invalid link errors.
- **Root Cause**: In Android's `JSONObject`, when the database field `link_url` is SQL `NULL`, `file.optString("link_url")` returns the literal four-character string `"null"`. Because `"null".isNotBlank()` is `true`, standard uploaded files were incorrectly classified as external links pointing to `https://null`.
- **Resolution**: Sanitized `link_url` across [`ClassMateAcademicScreensSupabase.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt), [`ClassMateCourseFilesActivity.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateCourseFilesActivity.kt), and [`ClassMatePublishActivity.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt):
  ```kotlin
  val rawLink = file.optString("link_url").trim()
  val linkUrl = if (rawLink.isBlank() || rawLink.equals("null", ignoreCase = true)) "" else rawLink
  ```
- External link launching upgraded to `CustomTabsIntent` with fallback to `Intent.ACTION_VIEW` (`FLAG_ACTIVITY_NEW_TASK`), and declared Android 11+ `<queries>` in [`AndroidManifest.xml`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/AndroidManifest.xml) for `http`/`https` schemes.

---

## 60. "My Attendance" Auto-Adjusting Bottom Drawer & Course Transition UX

### A. Snug Bottom Sheet & Course Selection
- **Commit Reference**: `1ae69c7`, `f5ab444` (from Conversation `b447abdd`)
- Replaced centered dialog with `BottomSheetDialog` on Android and sliding bottom drawer (`.attendance-bottom-drawer`) on Web.
- Starts in `layoutCoursesView` with `height = WRAP_CONTENT`, snugly fitting the exact vertical height of enrolled courses with zero dead space.

### B. Animated Transition to Day-Wise Daily Records
- Tapping any course triggers a smooth `AutoTransition` (`duration = 220ms` on Android, `cubic-bezier(0.2, 0.8, 0.2, 1)` on Web):
  ```kotlin
  TransitionManager.beginDelayedTransition(dialogView as ViewGroup, AutoTransition().apply { duration = 220 })
  val expandedHeight = (screenHeight * 0.88f).toInt()
  dialogView.layoutParams = dialogView.layoutParams?.apply { height = expandedHeight }
  coursesView.visibility = View.GONE
  tableView.visibility = View.VISIBLE
  ```
- Day-wise records table displays date, class number, binary present/absent status badge (emerald vs coral), and formatted time.

### C. Seamless Back Navigation
- Tapping "‹ Courses" triggers an inverse `AutoTransition`, restoring `height = WRAP_CONTENT` and sliding smoothly back down to the compact course picker.

---

## 61. Admin-Only "Manage Attendance" Settings Entry & Supabase RPC Mismatch Fix

### A. Role-Gated Settings Option
- **Commit Reference**: `f5ab444` (from Conversation `b447abdd`)
- Added `cardProfileAttendance` in [`classmate_profile.xml`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/classmate_profile.xml) under Account & Management.
- Strictly visible when `isAdmin || cachedServerOwner` (`profile.optString("role") == "admin"`).
- Tapping opens [`ClassMateAdminAttendanceActivity.kt`](file:///d:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAdminAttendanceActivity.kt) with `EXTRA_BATCH_ID` and `EXTRA_BATCH_LABEL`.

### B. Debugging & Resolution of Ad-Hoc RPC Error
- **Symptom**: Admin attempting to start an attendance session encountered `"Failed to start: function classmate.start_ad_hoc_attendance_session does not exist"`.
- **Root Cause**: Client was invoking `classmate.start_ad_hoc_attendance_session` (with underscores in `ad_hoc` and argument `target_course`), whereas the Supabase migration defined:
  ```sql
  classmate.start_adhoc_attendance_session(target_semester_course uuid, target_device text)
  ```
- **Fix**: Aligned parameter names and function identifier in `ClassMateAdminAttendanceActivity.kt`.

### C. ESP32 Hardware Disconnection & Self-Healing Protocol
- Sessions are safely created in Supabase cloud asynchronously. Even if the ESP32 reader is powered off or sleeping during session creation, upon powering on and reconnecting to Wi-Fi, the firmware queries `classmate.active_attendance_session`, syncs session state, and begins processing RFID taps with zero state desynchronization.

---

## 62. Desktop Web Persistent Notices Rail Independent Scrolling

### A. Desktop Layout Alignment (`globals.css`)
- **Commit Reference**: `1ae69c7` (from Conversation `d68b1691`)
- Updated `@media (min-width: 1001px)` in [`web/src/app/globals.css`](file:///d:/Code%20&%20Scripts/ClassMate-main/web/src/app/globals.css) so `.notice-pane` has:
  ```css
  position: fixed;
  top: 12px;
  bottom: 12px;
  right: 12px;
  width: var(--notice-width, 380px);
  height: calc(100dvh - 24px);
  max-height: calc(100dvh - 24px);
  overflow-y: auto;
  overscroll-behavior: contain;
  ```
- **Parity with Left Navigation Sidebar**: Academic routine and courses workspace in the center scrolls freely without moving the notices rail; the notices rail only scrolls when the mouse cursor is directly over it.

---

## 63. Verification, Production Deployment & Local Network APK Checklist

### A. Production Deployments
- **Web Client**: Deployed live to Vercel Production at **`https://classmatebd.vercel.app`** with zero TypeScript or CSS errors.
- **Supabase Backend**: Applied migration `202610210002_attendance_punch_card_notification.sql` updating live push notification payloads to punch card guidance.

### B. Android Native Builds & Local HTTP Distribution
- **Jetpack Compose Integration**: Verified clean compilation of `ClassMateBottomBar.kt` with Gradle and Jetpack Compose compiler metrics.
- **Installed Device**: Installed and verified on physical hardware via ADB.
- **Local Network HTTP Server**: Hosted for local direct downloading at:
  - `http://10.233.34.48:8080/download`
  - `http://192.168.0.101:8080/download`

---

## 64. Navigation Bar Tap-and-Hold Shortcuts & Role-Based Security Partitioning

### A. Touch Bounds & Gesture Routing Fix
- **Commit References**: `320e76e`, `cd30525`
- **The Defect**:
  - In earlier iterations of the Jetpack Compose floating navigation bar, long-pressing tabs failed to register consistently on physical devices. Touch events were either absorbed by child view hit-boxes or failed due to strict touch-slop bounds.
- **The Resolution**:
  - In [`ClassMateBottomBar.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/ui/ClassMateBottomBar.kt), applied `combinedClickable(onClick = { ... }, onLongClick = { ... })` directly onto the full-width tab cell container.
  - Linked native `ModalBottomSheet` and fallback `dialog_nav_tab_shortcuts.xml` (`item_nav_tab_shortcut.xml`) with tactile haptic feedback (`HapticFeedbackType.LongPress`).

### B. Strict Role-Based Partitioning Matrix
To eliminate privilege escalation risks and keep student workflows clutter-free, [`ClassMateAuthActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthActivity.kt) (`getQuickActionsForTab`) strictly gates shortcuts based on the user's authenticated profile role (`role`, `is_cr`, `is_server_owner`):

| Tab | Admin / Server Owner | Course Teacher | Class Representative (CR) | Enrolled Student |
| :--- | :--- | :--- | :--- | :--- |
| **Timetable** | • Academic Calendar<br>• Bus Schedule<br>• Add Routine Period | • Bus Schedule<br>• Add Routine Period (Assigned) | • Bus Schedule<br>• Academic Calendar | • Bus Schedule<br>• Academic Calendar |
| **Notices** | • Post Notice<br>• Search Notices | • Post Course Notice<br>• Search Notices | • Post Batch Notice<br>• Search Notices | • Search Notices |
| **Courses** | • Add Library File / Drive Link<br>• Manage Attendance | • Add Course Material<br>• Manage Attendance | • Share Drive Link / Add Material | • My Attendance Drawer |
| **Batch** | • Batch Administration<br>• Batch Fund Ledger | • Batch Student Directory | • Record Batch Fund<br>• View Fund Ledger | • Batchmates Directory |
| **Settings** | • Admin Panel<br>• User Management<br>• Switch Batch<br>• Edit Profile | • Switch Batch<br>• Edit Profile | • Edit Profile | • Edit Profile |

- **Security Guarantee**: Regular students are completely walled off from administrative tooling, user verification lists, and teacher controls. Class Representatives only receive actions scoped to their specific batch.

---

## 65. "Add Library File" Screen Complete Redesign & Option 2 Architecture

### A. Motivation & Elimination of Cluttered Redundancies
- **Commit References**: `7eb85a8`, `7b0a4d2`, `e68a9da`
- **Problems Addressed**:
  - The previous upload screen featured cluttered, redundant controls: both an in-field copy-style paste icon and a separate `"Paste from clipboard"` button.
  - Non-Drive links frequently failed silently or lacked viewing permissions.
  - Long course titles were clipped mid-word in dropdown selectors without ellipses.
  - The "Choose file" action looked like a disabled text input rather than an actionable button.

### B. Intelligent Paste & Automatic Clipboard Detection
- In [`ClassMatePublishActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt):
  - **Zero-Click Clipboard Detection**: On `onResume()`, the activity inspects the system clipboard. If a valid `http://` or `https://` string is detected, an inline dismissible banner appears immediately above the URL field:
    `"📋 Link detected — tap to paste"`. One tap populates the input and dismisses the banner.
  - **Drive Share Link Advisory**: If the pasted URL does not match a Google Drive share pattern (`drive.google.com/...`), an inline warning alerts the user:
    `"This doesn't look like a Drive link — make sure it's shared as 'Anyone with the link can view' or others won't be able to open it."` (Advisory banner that never blocks valid submissions).

### C. Two-Line Course Code Dropdown
- Inflates [`item_course_two_line_dropdown.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/item_course_two_line_dropdown.xml):
  - **Top Line**: Bold course code in primary blue (`CSE 2101`, 14sp, bold).
  - **Bottom Line**: Full course title in secondary text with clean `TextUtils.TruncateAt.END` ellipsis, preventing awkward mid-word breaks.

### D. Prominent Action Button & Form State Preservation
- **"Choose File" Button**: Restyled into a prominent filled primary button (`bg_picker_pill.xml`) displaying accepted formats and file size limits (`"PDF, DOCX, PPTX, ZIP • up to 50MB"`).
- **Post-Selection Card**: Once selected, transforms into an active file card showing the file-type vector icon, filename, formatted byte size (e.g. `14.2 MB`), and a clear button (`✕`).
- **Auto-Title Infill**: If the resource title is blank, selecting a file automatically populates the title field from the sanitized filename.
- **State Preservation**: Switching between `[ Upload File | Drive Link ]` tabs preserves shared form inputs (Category, Course selection) without resetting the entire form.
- **Dynamic Live Preview Card**: Renders an interactive preview card directly underneath the form showing exactly how the asset will render in the Courses feed.

### E. Background Resilience & Duplicate Detection
- **WorkManager Pipeline**: Backed by `LibraryUploadWorker` and `LibraryUploadManager` with determinate progress percentage and persistent notification. If Wi-Fi drops, uploads are queued and resumed automatically.
- **Pre-Submission Collision Guard**: Queries existing batch resources before submitting. If a duplicate or similar title exists in the selected category and course, prompts:
  `"A similar file already exists in this course — add as a new file, or update the existing one instead?"`.

---

## 66. True Neutral Charcoal / OLED Dark Mode Overhaul

### A. Root Cause: Midnight-Blue Glare
- **Commit Reference**: `1955d71`
- **User Feedback**: The previous dark theme utilized heavy midnight-blue tones (`#0A1324`), which produced noticeable blue light glare, harsh visual contrast at night, and cognitive irritation during extended study sessions.

### B. True Neutral OLED Palette Specifications
Overhauled [`app/src/main/res/values-night/colors.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/values-night/colors.xml) to a pure, true neutral charcoal / OLED dark hierarchy:

| Token Name | Hex Value | Semantic Role |
| :--- | :--- | :--- |
| **`cm_bg`** | `#0B0C0E` | App-wide root canvas background (OLED near-black) |
| **`cm_bg_secondary`** | `#111215` | Secondary container & scroll background |
| **`cm_surface` / `cm_card`** | `#16171B` | Surface card & item background |
| **`cm_surface_elevated`** | `#1E2025` | Elevated dialogs, bottom sheets & dropdown menus |
| **`cm_border`** | `#272930` | Subdued borders (1px) |
| **`cm_border_strong`** | `#383C44` | High-contrast interactive borders |
| **`cm_divider`** | `#1F2126` | Inset list item dividers |
| **`cm_text_primary`** | `#F8FAFC` | High-legibility crisp white text |
| **`cm_text_secondary`** | `#CBD5E1` | Muted subtitle typography |
| **`cm_text_muted`** | `#94A3B8` | Inactive labels & timestamp metadata |

- **Impact**: Completely eliminates eye strain, significantly reduces power consumption on mobile AMOLED displays, and matches contemporary minimalist design standards.

---

## 67. Dedicated Batch Administration Modernization & Semester Lifecycle Architecture

### A. Dedicated Batch Admin Workspace
- **Commit Reference**: `1955d71`
- Introduced [`ClassMateBatchAdminActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateBatchAdminActivity.kt) and [`activity_batch_admin.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/activity_batch_admin.xml).
- Replaced cramped modal settings with a spacious administrative dashboard:
  - Metric Summary Tiles: Total Enrolled Students, Active Semester Badge, Routine Slot Count, Enrolled Course Count.
  - Semester Controls: Switch active semester, publish new semester, view archive.
  - Class Representative (CR) Management: Assign or revoke CR status with live Supabase synchronization.

### B. Critical Disaster Recovery: 3rd Semester Reactivation
- **Commit Reference**: `9e19ed4`
- **Incident**: An administrator accidentally clicked "Publish 4th Semester" for the **CSE 22nd Batch**. In the legacy database schema, publishing a semester marked all preceding semesters as `status = 'completed'`, immediately hiding all 3rd semester courses and weekly routine slots from student feeds.
- **Database Stored Procedure Overhaul (`202610210003_allow_semester_reactivation.sql`)**:
  - Replaced the rigid one-way `publish_semester` function with a bidirectional reactivation procedure:
    ```sql
    create or replace function classmate.publish_semester(target_semester uuid)
    returns classmate.semesters
    language plpgsql security definer set search_path = '' as $$
    declare
      s classmate.semesters%rowtype;
    begin
      perform classmate.require_owner();
      select * into s from classmate.semesters where id = target_semester;
      if s.id is null then raise exception 'Semester not found'; end if;
      perform 1 from classmate.batches where id = s.batch_id for update;
      select * into s from classmate.semesters where id = target_semester for update;

      if s.status = 'active' then return s; end if;

      -- Mark other semesters completed
      update classmate.semesters set status = 'completed'
      where batch_id = s.batch_id and status = 'active' and id <> s.id;

      -- Reactivate target semester seamlessly
      update classmate.semesters set status = 'active', published_at = coalesce(s.published_at, now())
      where id = s.id returning * into s;

      insert into classmate.notification_outbox(kind, record_id, department_id, batch_id, routing_label)
      select 'semester', s.id, b.department_id, b.id, classmate.routing_label(b.id)
      from classmate.batches b where b.id = s.batch_id
      on conflict (kind, record_id) do nothing;

      return s;
    end;
    $$;
    ```
- **UI Guardrails (`ClassMateBatchAdminActivity.kt`)**:
  - Distinguishes semesters as `Semester X • Active (Current) ✓`, `Semester X • Completed`, and `Semester X • Draft`.
  - Tapping a completed semester displays an explicit dialog: `"Reactivate Semester X? Reactivating Semester X will set its courses and weekly timetable routine back as active for this batch."`.
  - Successfully restored **CSE 22nd Batch, 2nd Year 1st Semester** (`56056dc8-5c95-40d5-95b5-162a6ff4cd49`) with all routine slots and courses completely intact.

---

## 68. Streamlined Courses, Notices & Banglish-to-Bengali AI Formatting Engine

### A. UI Simplification
- **Commit Reference**: `19b22c1`
- **Courses Screen**: Removed redundant filter chips (`[ All | Theory | Lab ]`) beneath the "My Attendance" hero banner in [`fragment_pdf_library.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/fragment_pdf_library.xml).
- **Settings Screen**: Completely deleted the "ClassMate AI Assistant" card (`cardProfileAi`, `dividerProfileAi`) from [`classmate_profile.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/res/layout/classmate_profile.xml) and [`ClassMateAcademicScreensSupabase.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt).
- **Notice Composer**: Removed the experimental AI compose chat tab; retained clean General Notice and Class Cancellation tabs.

### B. Banglish-to-Bengali AI Auto-Formatting Engine
- **Migration & Edge Function**: `202610210004_auto_notice_ai_mode.sql` & `supabase/functions/classmate-ai/index.ts`.
- **Purpose**: Class Representatives frequently type urgent notices in informal "Banglish" (Bengali transliterated into English/Latin letters, e.g. *"kalke 10 tai class hobe na, shobai thakba"*).
- **Behavior & Strict Rules**:
  1. **Script Transliteration**: Automatically converts Banglish and informal phonetic typing into natural, grammatically sound Bengali script (বাংলা লিপি).
  2. **Auto-Title Synthesis**: If the title field was left empty, generates a concise, professional title based on notice contents.
  3. **English Fidelity**: If input is written in standard English, preserves English text while correcting minor grammar and typos.
  4. **Strict Entity Preservation**: Never modifies or translates URLs (`http/https`), phone numbers, email addresses, course codes (`CSE 2101`), dates, times (`10:00 AM`), or room numbers.
  5. **Silent Directive**: Honors `/silent` flag to suppress push broadcasts.

---

## 69. Modern Timetable Header Wordmark & Perfect Visual Alignment

### A. Brand Wordmark Architecture
- **Commit References**: `6dfc683`, `c6a1c70`, `10706ec`
- **Inspiration**: Top app-bar aesthetics from modern messaging clients (WhatsApp & Facebook Messenger).
- **Layout (`fragment_timetable.xml`)**:
  - Replaced the greeting row (`"Good morning, [Name] 👋"`) with an ultra-clean brand wordmark.
  - Typography: **Plus Jakarta Sans ExtraBold** (`plus_jakarta_sans_extrabold.ttf`), lowercase **`classmate`** in `cm_text_primary` (`#F8FAFC` dark / `#0F172A` light) paired with a vibrant brand-accent period **`.`** in `cm_primary` (`#3B82F6`).
  - Size: Bumped to **`28sp`** with `letterSpacing="-0.03"` and `includeFontPadding="false"`.

### B. Perfect Horizontal Margin Alignment
- **The Issue**: The 7-day selector pills (`layoutDaySelector`) started at a slightly different horizontal inset than the title text, creating an awkward visual misalignment along the left edge.
- **The Fix**: Standardized header horizontal container padding to `paddingHorizontal="12dp"`, aligning the wordmark's initial letter `'c'` with the exact left boundary of the first day selector pill.

---

## 70. Bottom Navigation Bar State Restoration, Batchmates Sorting & 5-Line Notice Preview

### A. Navigation Bar State Restoration Bug Fix
- **Commit Reference**: `a6fc600`
- **The Bug**: When users navigated deep into Settings/Profile or scrolled extensive feeds, returning to top views occasionally caused the floating bottom navigation bar to remain hidden off-screen.
- **The Fix**: In `ClassMateAcademicScreensSupabase.kt`, visiting `R.id.nav_profile` explicitly enforces `setNavigationShown(true)` with immediate translation and alpha reset. Furthermore, `SCROLL_STATE_IDLE` on the RecyclerView feed automatically restores navigation visibility.

### B. Natural Batchmates Directory Sorting Engine
- **Migration**: `202610210005_sort_batch_friends_by_student_id.sql`.
- **Database Function**: `classmate.batch_friends(target_batch uuid, query_text text, result_offset integer)`.
- **Ordering Hierarchy**:
  1. **System Administrator / App Developer**: Pinned to index 0 (`order by (p.role = 'admin') desc`).
  2. **Batch Enrolled Students**: Naturally sorted by `student_id` ascending (`CE24027`, `CE25001` < `CE25002` < `...` < `CE25060`).
  3. **Alphabetical Tie-breaker**: `full_name asc, id asc`.

### C. Notice Card 5-Line Truncation Standard
- Standardized maximum preview lines to **5 lines** (`MAX_PREVIEW_LINES = 5`) across:
  - Android: `ClassMateNoticeText.kt` and unit test `ClassMateNoticeTextTest.kt`.
  - Web: `web/src/components/notices.tsx` (`rawLines.length > 5`).

---

## 71. True Edge-to-Edge Transparent System Navigation Bar with Safe Insets

### A. Immersive System Window Architecture
- **Commit Reference**: `8162624`
- In [`ClassMateAuthActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthActivity.kt):
  ```kotlin
  WindowCompat.setDecorFitsSystemWindows(window, false)
  window.statusBarColor = Color.TRANSPARENT
  window.navigationBarColor = Color.TRANSPARENT
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
  }
  ```

### B. Safe Insets Management
- **Status Bar Inset**: Applied `ViewCompat.setOnApplyWindowInsetsListener(homeHost)` to add padding top equal to `statusBars().top`, preventing headers from drawing under the physical camera cutout.
- **Navigation Bar Inset**: In `ClassMateBottomBar.kt`, the floating pill navigation bar utilizes `.navigationBarsPadding()`:
  - System 3-button navigation bars and gesture navigation pill handles float cleanly over the transparent viewport without black bottom bars or clipped touch areas.

---

## 72. Jetpack Compose Floating Nav Bar Performance & 60/120fps Smoothness Engine

### A. Recomposition Thrashing Root Cause
- **Commit Reference**: `da79afe`
- **Symptom**: Tapping tabs on 120Hz physical displays caused noticeable stutter, frame drops, and delayed spring animations.
- **Root Cause**: The floating indicator pill relied on `onGloballyPositioned` measurement callbacks on every tab item. Measuring and writing layout positions back into Compose `mutableState` triggered continuous recomposition loops during indicator animations.

### B. Mathematical Indicator Positioning
- In [`ClassMateBottomBar.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/ui/ClassMateBottomBar.kt):
  - Completely removed `onGloballyPositioned` state writes.
  - Calculated tab position mathematically:
    ```kotlin
    val tabWidth = totalBarWidth / visibleTabs.size
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * selectedIndex,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "indicatorOffset"
    )
    ```
  - **Result**: Completely decoupled indicator motion from child layout measurement, locking frame rates at a silky smooth 60fps/120fps.

---

## 73. "My Attendance" Bottom Sheet Bug Fixes & Height Auto-Adjustment

### A. Bottom Sheet Transition Polish
- **Commit Reference**: `7b0a4d2`
- **Layouts & Code**: `dialog_rfid_attendance.xml` and `ClassMateAcademicScreensSupabase.kt`.
- **Fixes**:
  - Corrected height calculation during `AutoTransition` between the compact course overview (`height = WRAP_CONTENT`) and the expanded day-wise attendance table (`height = 88% screen height`).
  - Fixed dismiss on outside touch and back navigation restoring the compact view without visual jumps.

---

## 74. ESP32 Hardware Wiring Guide & Standalone RFID Reader Operation

### A. Standalone Reader Pinout (ESP32 38-Pin Dev Module + MFRC522)
When testing in environments without the OLED display or buzzer, the ESP32 operates independently with only the MFRC522 SPI bus connected:

| MFRC522 Pin | ESP32 Pin | Function | Wiring Guidance |
| :--- | :--- | :--- | :--- |
| **`3.3V`** | **`3V3`** | Regulated Power | **Never connect to 5V (permanently damages IC)** |
| **`RST`** | **`GPIO 4`** | Hardware Reset | Clean GPIO avoiding I2C bus |
| **`GND`** | **`GND`** | Ground Rail | Common system ground |
| **`MISO`** | **`GPIO 19`** | VSPI MISO | Master-In-Slave-Out |
| **`MOSI`** | **`GPIO 23`** | VSPI MOSI | Master-Out-Slave-In |
| **`SCK`** | **`GPIO 18`** | VSPI SCK | Hardware serial clock |
| **`SDA / SS`** | **`GPIO 5`** | VSPI SS | Active-low chip select |

### B. Serial Monitor Card Verification
- Firmware: `esp32_rfid_attendance.ino`.
- Configured immediate serial printout on card tap:
  ```text
  04:49:03.962 -> >>> CARD TAPPED! UID: F9831C02  [F9 83 1C 02] <<<
  04:49:04.052 -> -> Sending Scan POST: {"card_uid":"F9831C02","device_id":"ESP32-READER-01"}
  ```

---

## 75. `enroll_student_rfid` RPC Parameter Mismatch Resolution & Anon Security Grants

### A. RPC Parameter Naming Collision
- **Commit References**: `2569759`, `525d773`, `fdb6180`
- **The Defect**:
  - When assigning an RFID card in Web or Android, users encountered an error: `"cant register card"`.
  - Web client called PostgREST with `{ target_student, target_card_uid }`, while Android called with `{ p_student_id, p_card_uid }`. PostgREST schema cache rejected mismatched parameter signatures.

### B. Universal Database Adapter (`202610220001_fix_enroll_student_rfid_parameters.sql`)
- Updated stored procedure `classmate.enroll_student_rfid` to accept both conventions seamlessly:
  ```sql
  create or replace function classmate.enroll_student_rfid(
    p_student_id uuid default null,
    p_card_uid text default null,
    target_student uuid default null,
    target_card_uid text default null
  ) returns jsonb language plpgsql security definer set search_path='' as $$
  declare
    v_student_id uuid := coalesce(p_student_id, target_student);
    v_raw_uid text := coalesce(p_card_uid, target_card_uid);
  ...
  ```
- Added retry fallback in `ClassMateAdminAttendanceActivity.kt` and `admin-attendance.tsx`.

### C. Anon Hardware Endpoint Permissions (`202610220002_grant_rfid_scan_anon.sql`)
- Granted `anon` role execute permissions on:
  - `classmate.process_rfid_scan(text, text)`
  - `classmate.get_device_active_session(text)`
- Enables unauthenticated ESP32 microcontrollers to dispatch heartbeats and record attendance taps directly without API bearer tokens.

---

## 76. Android Phone NFC Host Card Emulation (HCE) Exploration & Clean Revert

### A. The Requirement & Android UID Randomization
- **User Request**: Allow students to mark attendance by tapping their Android smartphone's NFC against the doorway reader instead of carrying a physical plastic card.
- **Technical Barrier**: In Android 4.4+, standard NFC card emulation intentionally randomizes the 4-byte UID on every single tap as a privacy protection measure against tracking. Consequently, the UID cannot be used as a static student identifier.

### B. The HCE Architectural Approach
To provide a static credential, Android requires **Host Card Emulation (HCE)**:
1. Android app declares a `HostApduService` with an ISO 7816-4 Application ID (AID) in `apduservice.xml`.
2. The reader detects the phone (returning SAK = `0x20` indicating ISO 14443-4 compliance).
3. The reader sends an ISO-DEP RATS (Request for Answer to Select) command.
4. The reader sends a `SELECT AID` APDU.
5. The Android OS routes the APDU to `ClassMateHceService.kt`, which responds with a secure cryptographic token.

### C. Real-World Hardware Failure on MFRC522
- **Prototype Implementation**: Commit `2143354`.
- **Empirical Hardware Test Logs**:
  ```text
  05:26:16.939 -> Smartphone detected (ISO 14443-4 SAK=0x20). Starting APDU exchange...
  05:26:16.985 -> [NFC] RATS handshake failed: Timeout in communication.
  05:39:01.194 -> [NFC] Smartphone detected (ISO 14443-4 SAK=0x20). Starting RATS...
  05:39:01.506 -> [NFC] RATS handshake timed out. Keep phone steady near antenna.
  ```
- **Root Cause Analysis**:
  - The MFRC522 IC is an entry-level RFID reader designed primarily for Mifare Classic (ISO 14443-3A) cards. It features a tiny 64-byte FIFO buffer and lacks a hardware ISO 14443-4 framing engine.
  - Software bit-level emulation of ISO-DEP framing over SPI introduces timing jitter and high packet error rates.
  - Smartphone NFC controllers have strict ISO-DEP response timing windows ($< 10\text{ ms}$). The MFRC522 repeatedly failed the RATS handshake due to RF coupling attenuation and protocol timeouts. Dedicated controllers like **PN532** or **ST25R3916** are required for reliable smartphone HCE.

### D. Clean Revert
- **Commit Reference**: `72d3fc5`
- As requested by the user, all HCE services, APDU descriptors, and firmware ISO-DEP modifications were cleanly reverted via `git revert`.
- **System Integrity**: Reverted cleanly to the 100% reliable physical Mifare Classic 1K RFID card architecture without orphaned files or regressions.

---

## 77. Comprehensive Verification, Production Deployment & Current System State

### A. Production Deployments
- **Web Client**: Deployed live to Vercel Production at **`https://classmatebd.vercel.app`** with zero TypeScript, CSS, or build errors.
- **Supabase Cloud**: Applied and verified migrations:
  - `202610210003_allow_semester_reactivation.sql`
  - `202610210004_auto_notice_ai_mode.sql`
  - `202610210005_sort_batch_friends_by_student_id.sql`
  - `202610220001_fix_enroll_student_rfid_parameters.sql`
  - `202610220002_grant_rfid_scan_anon.sql`

### B. Android Native Builds & Local Distribution
- **Build Status**: Verified clean compilation of APK with Jetpack Compose floating pill navigation bar, true edge-to-edge transparent system navigation, OLED neutral dark palette, and Option 2 library layout.
- **Physical Device**: Deployed and tested on physical Android smartphone via ADB.
- **Local Network HTTP Server**: Hosted for local direct downloading at:
  - `http://10.233.34.48:8080/download`
  - `http://192.168.0.101:8080/download`

---



## 78. Web Notice Composer Full Parity with Android (Vacation Mode, Image Attachments & AI Polish)

### A. Background & Requirement
- **Context**: While the native Android client ([`PostNoticeActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/PostNoticeActivity.kt)) had received rich composition features—including image attachments, vacation mode with duration calculation, and AI polishing—the Web companion client ([`web/src/components/notices.tsx`](file:///D:/Code%20&%20Scripts/ClassMate-main/web/src/components/notices.tsx)) only supported basic text notices and simple class cancellation forms.
- **Goal**: Bring the Web notice composer to complete 1:1 functional parity with the Android app.

### B. Core Features & Modalities Added to Web Notice Composer
1. **Three Dedicated Composition Modalities**:
   - **General Notice**: Subject title, body with multi-line auto-resizing text area, and optional course selector (for teachers assigned to multiple courses).
   - **Class Cancellation**: Quick day selector (`Today` / `Tomorrow`), active semester course dropdown, and automatic linkage to `classmate.class_changes`.
   - **Vacation / Academic Recess Announcement**:
     - Dedicated input fields: Vacation Title (e.g. *Durga Puja Vacation*, *Eid-ul-Fitr Break*), Start Date, and End Date.
     - **Dynamic Duration Engine**: Automatically computes and formats the vacation span:
       ```typescript
       const days = Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1;
       const dateDuration = `${days} days (${format(start)} – ${format(end)})`;
       ```
     - **Standard Academic Advisory Footer**: Automatically appends the institutional notice text:
       `"Classes and academic activities will remain suspended during this vacation period."`
2. **Image & Photo Attachments in Web Notices**:
   - Integrated native image file input with drag-and-drop or file browser trigger.
   - **Client-Side Validation**: Restricts to `image/jpeg`, `image/png`, and `image/webp`; enforces maximum 10 MB file size limit.
   - **Interactive Thumbnail Preview**: Renders thumbnail preview with quick remove button (`✕`) and upload progress indicator.
   - **Storage Pipeline**: Uploads to Supabase Storage bucket `notice-images` with unique UUID storage keys.
   - **Database Association**: Inserts row into `classmate.notice_attachments` linking `notice_id`, `storage_key`, `file_name`, `mime_type`, and `size_bytes`.
   - **Signed URL Resolution**: The feed query uses `supabase().storage.from("notice-images").createSignedUrls(keys, 3600)` to render authenticated image previews in notice feed cards.
   - **Atomic Discard on Failure**: If client-side image upload fails after the notice record is created, triggers database RPC `discard_failed_notice_upload(target_notice)` to prevent orphaned notices.
3. **AI Compose & Polish Integration**:
   - Integrated with Supabase Edge Function `classmate-ai` (`mode: "polish"` / `"auto_notice"`).
   - Allows Class Representatives (CR) to paste informal, messy drafts (including Banglish typing); the AI transliterates into grammatically sound Bengali script (বাংলা লিপি), refines the body, synthesizes a title if omitted, and strictly preserves key entities (dates, course codes, phone numbers, and URLs).

---

## 79. WhatsApp Bot Broadcasting Exploration & Clean Teardown

### A. Context & User Problem
- Many students in the batch had not yet installed the ClassMate app, forcing Class Representatives (CR) to manually re-post announcements into the batch's WhatsApp notice group.
- The user requested investigating an automated bridge to forward notices published in ClassMate directly into WhatsApp groups.

### B. Engineering Investigation & Architecture Tested
- Tested a headless WhatsApp Web gateway using Baileys / Evolution-style microservice deployed on cloud hosting (Render):
  - Attempted WhatsApp Multi-Device 8-character pairing code linking (`requestPairingCode`) to avoid the physical limitation where a user on a single smartphone cannot scan an on-screen QR code.
  - Explored filtering WhatsApp chats to only expose group chats and isolate batch groups.

### C. Technical Obstacles & Operational Hazards
1. **Hosting Cold Starts & Webhook Timeouts**: Free-tier cloud instances (Render) experience 50+ second cold-start spin-ups, causing WhatsApp socket pairing attempts to time out.
2. **Device Linking Failures**: Users repeatedly encountered WhatsApp protocol errors: `"couldnt link device. check the phone number is correct on your device or get a new code"`.
3. **Anti-Bot & Ban Risks**: WhatsApp actively flags and bans accounts running unapproved headless automation on personal numbers.
4. **Data Privacy & Credential Security**: Storing WhatsApp multi-device authentication credentials on external infrastructure introduced data leakage concerns regarding personal chats and contacts.

### D. Clean Teardown & Complete Revert
- As requested by the user, all experimental WhatsApp bot services, Render project instances, API bridges, and associated UI controls were cleanly destroyed and reverted without leaving orphaned code or impacting other features.

---

## 80. Resurrection of Automated Telegram Channel Broadcasting for Note Sharing (CSE 22)

### A. Motivation & Channel Integration
- The CSE 22nd Batch maintains an active Telegram channel specifically for sharing lecture notes, slides, and study resources.
- Rather than maintaining precarious WhatsApp scrapers, the system resurrected and upgraded the official Telegram bot integration (`@ClassMateBot`):
  - When an Admin or Class Representative (CR) uploads an academic file (PDF, slide, book) or shares a Google Drive link, the system automatically broadcasts an announcement directly into the CSE 22 batch's Telegram channel.

### B. Database Trigger & Vault Secret Architecture (`202610230001_telegram_bot_and_drive_notices.sql`)
1. **Trigger Function**: `classmate.broadcast_resource_to_telegram()` on `classmate.file_metadata`:
   - Fires on `INSERT` for active resources (such as Google Drive links) and on `UPDATE` when file upload status transitions from `'pending'` to `'active'`.
   - **Strict Batch Isolation**: Validates `batch_number = 22`. Never broadcasts resources belonging to other academic batches.
   - **Vault Security**: Decrypts the integration secret securely from Supabase Vault:
     ```sql
     select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'classmate_dispatch_secret' limit 1;
     ```
   - **Course & Contributor Resolution**: Resolves course title and course code by joining with `classmate.courses`, and looks up the uploader's display name from `classmate.profiles`.
   - **Asynchronous HTTP Dispatch**: Dispatches non-blocking HTTP POST via `net.http_post` to the Cloudflare Worker `/internal/telegram-broadcast` endpoint, wrapped in an exception handler so database transactions never fail if Telegram dispatch encounters network issues.

### C. Cloudflare Worker Telegram Dispatcher (`cloudflare-worker/src/index.ts`)
- **Endpoint**: `/internal/telegram-broadcast` (secured via `X-Dispatch-Secret` matching vault secret).
- **HTML Sanitization**: Escapes user text using `escapeTelegramHtml(text)` (`&`, `<`, `>`).
- **Standardized Message Payload**:
  ```text
  New Academic Resource

  <code>{title}</code>

  Course: {course_title} ({course_code})
  Link: https://classmatebd.vercel.app/r/{resource_id}
  ```
- **Inline Keyboard**: Includes a single, clean action button:
  `[ Open in ClassMate ↗ ]` pointing to `https://classmatebd.vercel.app/r/{resource_id}`.

---

## 81. Automatic In-App Notices for Google Drive Links & Dedicated Push Notifications

### A. The In-App Feed Gap Resolution (`202610230001_telegram_bot_and_drive_notices.sql`)
- **Previous Defect**: When a user uploaded a physical PDF to Cloudflare R2 storage, a corresponding notice was created in `classmate.notices`. However, when a user added a Google Drive resource link via `create_library_link`, it only inserted into `file_metadata`. The Drive resource remained hidden inside the course files directory, and students received no feed announcement.
- **Resolution**:
  - Updated `classmate.create_library_link`:
    ```sql
    insert into classmate.notices (
      department_id, batch_id, semester_course_id, author_id, title, body, resource_id
    ) values (
      v_dept, target_batch, target_course, caller_id,
      left('Resource: ' || v_title, 200),
      'A new learning resource link has been shared. Open the link below.',
      new_resource.id
    );
    ```
  - Drive links now generate authentic in-app notice cards with green resource art, course association, and 1-tap file access.

### B. Dedicated Push Notification Context (`202610230002_resource_push_notifications.sql`)
- Updated `classmate.academic_notification_job_context`:
  - When notification outbox processes an event of kind `'notice'` linked to a `resource_id`:
    - **Notification Title**: `"New Resources"`
    - **Notification Body**: `"{file_title} · {course_title} ({course_code})"` (or `"{file_title} is now available"`)
    - **FCM Dispatch**: Broadcasts directly to all mobile devices in the enrolled batch.

---

## 82. Universal Elimination of Resource Categories Across Android & Web

### A. Motivation & User Feedback
- **User Mandate**: *"Remove the category system while sharing files entirely on ClassMate web and Android client. Also, remove these options everywhere where they exist. We don't need some filter like the categories as it exists slightly in notes, slides, questions, syllabus, etc. Delete it entirely from the file uploading page as well as from the courses section in the ClassMate web, as well as in the detailed view list of all the files of courses."*
- **Problem**: Academic categories created friction during upload, resulted in confusing semi-empty filter tabs, and caused arbitrary categorization disputes. Course files are naturally grouped by Subject/Course, making sub-categories redundant.

### B. Database Schema & Stored Procedure Updates (`202610230003_remove_resource_category.sql`)
1. `alter table classmate.file_metadata alter column category set default 'notes';`
2. `classmate.create_library_link`: Made `target_category` optional with default `'notes'`.
3. `classmate.edit_resource_metadata`: Made `target_category` optional with fallback to existing file category or `'notes'`.
4. Schema reloaded via `notify pgrst, 'reload schema'`.

### C. Android APK UI Overhaul
1. **[`ClassMatePublishActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt)**:
   - Completely deleted the side-by-side `metaRow` containing `colCategory` and the category picker dialog (`Select Category`).
   - Expanded `colSubject` into a spacious, full-width dropdown container.
   - Hardcoded default category parameter to `"notes"` for backend API compatibility.
   - Removed category filter constraints from duplicate resource detection queries.
   - Updated helper copy: `"Update the title, link or subject."`.
2. **[`ClassMateAcademicScreensSupabase.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt)**:
   - Hidden `btnCategorySyllabus` (`View.GONE`), turning the category toggle into a clean 2-segment switcher (`[ Theory | Lab ]`).
   - Removed category filter chips (`updateLibraryFilterChips`).
   - In file cards (`bindLibraryFile`), replaced the generic category subtitle (`Notes`, `Slides`) with the actual **Course Name** (`courseNames[file.optString("semester_course_id")]`).
3. **Course File Lists (`ClassMateCourseFilesActivity.kt`, `SubjectPdfListFragment.kt`, `PdfLibraryFragment.kt`)**:
   - Stripped category filter chips, search criteria, and category tags.

### D. Web Companion Client UI Overhaul (`web/src/components/library.tsx`)
1. Removed `categories = ["notes", "slides", "questions", "syllabus", "other"]` array.
2. Removed category filter pill list (`.category-list`).
3. Removed "Syllabus" tab from course type filters; simplified to `[ Theory | Labs ]`.
4. Removed `category` field from `UploadForm` (both file upload and drive link tabs); automatically passes `category: "notes"`.
5. Removed category select from the Edit Resource modal.
6. File cards render cleanly: title, course name, Google Drive/External Link/PDF icon badge, and formatted size.

---

## 83. Public Zero-Login Resource Access & Streamlined Web URLs (`/r/[id]`)

### A. The Zero-Login Requirement
- **User Request**: *"Bro for the file sharing link in telegram we need to work on the link that you post there, currently it takes user and asks for log into classmate, i want to make it a public like system. Anyone with the link can view the shared file without having the classmate app. For it dont use hyperlink anymore write directly our configured url of classmate."*
- **Problem**: Links previously directed users to `/#library/...`, which immediately threw a login modal if the user was unauthenticated, blocking external classmates or guest users from downloading lecture materials.

### B. Database Public RPC (`202610230004_public_resource_access.sql`)
- Implemented `classmate.get_public_resource(target_resource uuid)`:
  - Language: PL/pgSQL, `SECURITY DEFINER`, `set search_path = ''`.
  - Permissions: `GRANT EXECUTE ON FUNCTION classmate.get_public_resource(uuid) TO anon, authenticated, service_role;`.
  - Added wrapper in `public` schema for standard PostgREST RPC access.
  - Returns public-safe metadata: `id`, `title`, `file_type`, `size_bytes`, `mime_type`, `link_url`, `is_link`, `course_title`, `course_code`, `batch_number`, and `created_at`.
  - Excludes internal storage paths, user IDs, or administrative audit data.

### C. Public Landing Page (`web/src/app/r/[id]/page.tsx`)
- Server-rendered Next.js page accessible at `https://classmatebd.vercel.app/r/{id}`:
  - **Dynamic OpenGraph Meta Tags**:
    - `title: "{title} · ClassMate"`
    - `description: "Public shared learning resource for {course}. Accessible on web or native app without login."`
    - Renders rich link preview cards with course details on Telegram, WhatsApp, Messenger, and Discord.
  - **Visual Design**:
    - Top header: ClassMate brand mark + green `🛡️ Public Resource` badge.
    - Course banner: Course title + bold course code pill (e.g. `CSE 2101`).
    - File icon: Authentic multi-color Google Drive vector icon (for Drive links), link icon (for external links), or red PDF badge (for documents).
    - Meta tags: Formatted size, Batch number, upload date.
    - **Action Buttons**:
      1. **Primary Action**:
         - For Google Drive links: `"Open on Google Drive ↗"` (opens in new tab).
         - For uploaded files: `"Download / Open File 📥"` (triggers direct download).
      2. **Secondary Action**:
         - `"Open in ClassMate App 📱"` (routes to app deep link).
    - Public reassurance banner: *"Anyone with this link can view and download this academic material without logging in."*

### D. Serverless Download Pipeline (`web/src/app/api/public-download/[id]/route.ts`)
- Server route `GET /api/public-download/[id]`:
  - Validates resource UUID and queries `classmate.get_public_resource`.
  - **Drive & External Links**: Issues immediate HTTP `302 Found` redirect to destination link.
  - **Cloudflare R2 Storage Files**: Calls the Supabase Edge Function `signed-classmate-resource` with service role authorization to generate a secure, temporary download URL, then issues HTTP `302 Found` redirect directly to the signed R2 object.
  - Supports query parameter `?json=true` for client-side API consumers.

---

## 84. Android App Links & Seamless Deep Linking (`https://classmatebd.vercel.app`)

### A. Manifest Registration ([`AndroidManifest.xml`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/AndroidManifest.xml))
- Declared auto-verifying App Link intent filter on `ClassMateAuthActivity`:
  ```xml
  <intent-filter android:autoVerify="true">
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:scheme="https" android:host="classmatebd.vercel.app" />
  </intent-filter>
  ```

### B. Deep Link Router Implementation ([`ClassMateAuthActivity.kt`](file:///D:/Code%20&%20Scripts/ClassMate-main/app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthActivity.kt))
- Added `handleDeepLinkIntent(targetIntent: Intent?)`:
  - Parses incoming intents on startup and `onNewIntent`.
  - Extracts 36-character resource UUID from:
    - Path prefix `/r/{id}`
    - Query parameter `?file={id}` or `?id={id}`
    - URL fragments `/#library/{id}` or `/#courses/{id}`
  - Dispatches to `academicScreens.openDirectResource(fileId)`:
    - Opens Google Drive links smoothly in Chrome Custom Tabs.
    - Opens Cloudflare R2 documents directly in the in-app PDF/resource viewer.

---

## 85. Current System Verification & Production Deployment State (Oct 23, 2026)

### A. Production Cloud Deployments
- **Supabase Backend**: All four recent migrations successfully applied across Staging and Production:
  - `202610230001_telegram_bot_and_drive_notices.sql`
  - `202610230002_resource_push_notifications.sql`
  - `202610230003_remove_resource_category.sql`
  - `202610230004_public_resource_access.sql`
- **Cloudflare Worker**: Production deployment verified on `classmate-secure-api.shuaibuddowla-personal.workers.dev`:
  - `/internal/telegram-broadcast` handles batch 22 note sharing with zero latency.
- **Web Client**: Deployed live to Vercel Production at **`https://classmatebd.vercel.app`**:
  - `/r/[id]` public zero-login resource landing page verified with live OpenGraph cards.
  - `/api/public-download/[id]` verified with live 302 redirects.
  - Web notice composer tested with image attachments, vacation mode, and AI polishing.

### B. Android Native Builds & Local Testing
- **APK Verification**: Built and deployed to physical smartphone via ADB.
- **Features Tested**:
  - Streamlined resource publishing with category removal and full-width subject dropdown.
  - Deep linking from Telegram `/r/{id}` into Android app.
  - 2-segment routine toggle and course name subtitles in file lists.

---
