# Notices Feed Performance & Multi-Tier Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the Notices feed into a buttery-smooth 60/120fps list with sub-30ms instant offline startup, eliminating main-thread parsing/regexes, repetitive `findViewById` lookups, and SharedPreferences mega-blobs.

**Architecture:** Heterogeneous `ListAdapter<NoticeFeedRow>` with static ViewHolders, background pre-computation of presentation models on `Dispatchers.Default`, payload-based partial diffing for zero-flash reactions/reads/polls, and an asynchronous user/batch partitioned disk L2 cache.

**Tech Stack:** Kotlin, Android Views, RecyclerView, ListAdapter, DiffUtil, Kotlinx Coroutines, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-22-notices-feed-performance-and-caching-design.md`

## Global Constraints

- Android API level floor: 26 (Android 8.0 Oreo).
- Free-Tier Zero Added Compute: No polling, no extra RPC calls to Supabase.
- Strict User & Batch Isolation: Persistent cache must be isolated by `userId` and `batchId` (`notices_cache_${userId}_${batchId}.json`).
- Backward Compatibility: Zero regressions on image grids, PDFs, polls, reactions, and assessment deep links.

## Review Focus

1. **Empty / Null Fields**: Missing `published_at`, empty `title`, or blank `body` must format gracefully without crashing or throwing `DateTimeParseException`.
2. **Assessment Tag Leaks**: Body strings containing `[assessment_id:XYZ]` or legacy `[assessment_id:]` must be stripped cleanly from visible text while correctly linking to assessment bottom sheets.
3. **Poll Voting Zero-Divide**: Poll with 0 total votes must display 0% without division-by-zero or NaN rendering bugs.
4. **Offline Startup**: Launching with no internet must hydrate and display the cached feed in < 30ms with no empty screen or endless shimmer.
5. **Payload Partial Updates**: Tapping Like or voting must not reset scroll position, collapse expanded text, or flash view holders.

---

### Task 1: Notice Presentation Models & Pre-computation Mapper

**Files:**
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticeFeedModel.kt`
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticePresentationMapper.kt`
- Test: `app/src/test/java/com/shuaib/classmate/notices/NoticePresentationMapperTest.kt`

**Interfaces:**
- Produces: `sealed interface NoticeFeedRow`, `data class NoticePresentationModel`, `data class PollPresentationModel`, and `NoticePresentationMapper.mapToPresentation(rawItem: JSONObject, ...): NoticeFeedRow.NoticeCard`

- [x] **Step 1: Write the failing unit tests for `NoticePresentationMapper`**
  Cover relative date parsing, `[assessment_id:]` tag extraction, clean text formatting, and poll percentage calculations.

- [x] **Step 2: Run test to verify it fails**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticePresentationMapperTest`
  Expected: FAIL with missing classes.

- [x] **Step 3: Implement `NoticeFeedModel.kt` and `NoticePresentationMapper.kt`**
  Implement the immutable data classes and mapping functions on background dispatchers.

- [x] **Step 4: Run test to verify it passes**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticePresentationMapperTest`
  Expected: PASS.

---

### Task 2: User- and Batch-Isolated Multi-Tier Disk Cache Engine

**Files:**
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticeCacheStore.kt`
- Test: `app/src/test/java/com/shuaib/classmate/notices/NoticeCacheStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object NoticeCacheStore {
      suspend fun saveCache(context: Context, userId: String, batchId: String, payload: String)
      suspend fun readCache(context: Context, userId: String, batchId: String): String?
      fun clearUserCache(context: Context, userId: String)
  }
  ```

- [ ] **Step 1: Write unit test for `NoticeCacheStore`**
  Test saving and reading cache files partitioned by `userId` and `batchId`, and clearing on logout.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticeCacheStoreTest`
  Expected: FAIL with unresolved reference.

- [ ] **Step 3: Implement `NoticeCacheStore.kt`**
  Write async atomic file writer using private app storage (`context.filesDir/notices_cache_${userId}_${batchId}.json`) with atomic `.tmp` rename to prevent corruption.

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticeCacheStoreTest`
  Expected: PASS.

---

### Task 3: Optimized `NoticeModernViewHolder` with Cached View References & Payload Binding

**Files:**
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticeModernViewHolder.kt`
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticePayload.kt`

**Interfaces:**
- Consumes: `NoticePresentationModel`, `NoticePayload`
- Produces: `NoticeModernViewHolder(view: View)` caching all 30+ child views; `bind(item: NoticePresentationModel, callbacks: NoticeCardCallbacks)`; `bindPayloads(payloads: List<Any>)`

- [ ] **Step 1: Define `NoticePayload` sealed classes**
  `NoticePayload.Like(isLiked: Boolean, count: Int)`, `NoticePayload.Read(isRead: Boolean, count: Int, avatars: List<String>)`, `NoticePayload.PollVote(poll: PollPresentationModel)`, `NoticePayload.Translation(translatedText: String?, isTranslating: Boolean)`, `NoticePayload.Expand(isExpanded: Boolean)`.

- [ ] **Step 2: Implement `NoticeModernViewHolder`**
  Cache child views during construction. Implement full binding and partial payload binding to update target elements without re-binding the card.

- [ ] **Step 3: Verify build compilation**
  Run: `.\gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.

---

### Task 4: Heterogeneous `NoticeFeedAdapter` with Payload Diffing

**Files:**
- Create: `app/src/main/java/com/shuaib/classmate/notices/NoticeFeedAdapter.kt`
- Test: `app/src/test/java/com/shuaib/classmate/notices/NoticeFeedAdapterDiffTest.kt`

**Interfaces:**
- Consumes: `NoticeFeedRow`, `NoticeModernViewHolder`, `NoticePayload`
- Produces: `class NoticeFeedAdapter : ListAdapter<NoticeFeedRow, RecyclerView.ViewHolder>(DIFF_CALLBACK)`

- [ ] **Step 1: Write unit test for `NoticeFeedDiffCallback`**
  Verify `areItemsTheSame`, `areContentsTheSame`, and payload generation (`getChangePayload`) for like count, read count, poll vote, and translation changes.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticeFeedAdapterDiffTest`
  Expected: FAIL.

- [ ] **Step 3: Implement `NoticeFeedAdapter.kt`**
  Implement multi-viewType adapter: DateHeader (`item_notice_date_header`), NoticeCard (`item_notice_modern`), and PagingFooter (`item_notice_paging`).

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew testDebugUnitTest --tests com.shuaib.classmate.notices.NoticeFeedAdapterDiffTest`
  Expected: PASS.

---

### Task 5: Integrate Pipeline into `ClassMateAcademicScreensSupabase.kt` & SWR

**Files:**
- Modify: `app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt`

**Interfaces:**
- Consumes: `NoticeCacheStore`, `NoticeFeedAdapter`, `NoticePresentationMapper`

- [ ] **Step 1: Wire Instant SWR Startup**
  On screen load, read L2 disk cache asynchronously, map to `NoticeFeedRow` on `Dispatchers.Default`, and submit to `NoticeFeedAdapter` (<20ms). Shimmer is dismissed immediately if cache exists.

- [ ] **Step 2: Wire Background Revalidation Pipeline**
  Fetch `rows("notices")` + `rpc("notice_feed_details")` together, merge with loaded older notices, map on `Dispatchers.Default`, and submit. Asynchronously persist snapshot to `NoticeCacheStore`.

- [ ] **Step 3: Wire Payload Triggers**
  Update likes, reads, translation toggles, and poll voting to trigger payload changes without full card rebinds.

- [ ] **Step 4: Configure RecyclerView Optimizations**
  Set `setItemViewCacheSize(10)`, disable blink animations on item animator.

- [ ] **Step 5: Verify build**
  Run: `.\gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.

---

### Task 6: Device Verification & Performance Profiling

**Files:**
- Execution on phone: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Assemble and install APK on phone**
  Run: `.\gradlew assembleDebug; adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: Profile frame rendering performance**
  Run: `adb shell dumpsys gfxinfo com.shuaib.classmate reset`
  Fling/scroll the Notices feed up and down vigorously.
  Run: `adb shell dumpsys gfxinfo com.shuaib.classmate`
  Verify: 99th percentile frame times < 16.6ms (zero jank).

- [ ] **Step 3: Verify Offline Startup**
  Toggle device to Airplane mode, kill app, restart, open Notices. Verify cached notices render instantly with 100% full content.

- [ ] **Step 4: Verify Payload Diffing & Interactive Elements**
  Tap Like button, expand/collapse text, open assessment bottom sheet. Verify zero screen flicker or scroll jumps.
