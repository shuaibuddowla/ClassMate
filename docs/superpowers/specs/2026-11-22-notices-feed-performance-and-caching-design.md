# Design Specification: Notices Tab Performance & Multi-Tier Caching

**Date**: 2026-11-22  
**Target Module**: Android App (`app`) — Notices Feed & Caching Engine  
**Status**: Ready for Implementation (Reviewed & Clarified)  

---

## 1. Intent & Context

The **Notices feed** is ClassMate's most active and data-dense tab. A single notice card can include:
- Author verification, avatar, and role badges
- Dynamic illustrations and category themes (General, Resource, Cancel, Poll)
- Attachments (images, PDFs, and links)
- Interactive polls with live voting bars and vote counts
- Translations and expandable body text
- Read receipts, read-by-others avatars, comments count, and like counters
- Inline assessment announcement cards with deep linking to Upcoming Assessments

As batch history grows, scrolling and opening the Notices tab exhibits micro-stutters and dropped frames. This design addresses the core architectural causes with an industry-standard rendering and caching pipeline.

---

## 2. Goals, Non-Goals & Anti-Goals

### Goals
- **Jank-Free 60/120fps Scrolling**: Eliminate all main-thread parsing, regex replacements, and repetitive `findViewById` lookups during list scrolling.
- **Sub-30ms Feed Startup (Stale-While-Revalidate)**: Render the cached feed instantly on app launch or tab switch before issuing any network requests.
- **Isolated Multi-Tier Cache**: Replace the monolithic SharedPreferences JSON mega-blob with a structured, user- and batch-isolated persistent cache that doesn't choke main-thread I/O.
- **Partial Payload Diffing**: Update small card states (likes, read status, poll votes, translation toggles) without re-binding or re-animating the whole notice card.

### Non-Goals
- Full rewrite of notice cards into Jetpack Compose (retains XML `item_notice_modern` to avoid regressions in rich features like image grids, poll animations, and complex spans).
- Modifying backend Supabase schema or edge functions.

### Anti-Goals
- Increasing compute or egress load on the Supabase free tier.
- Breaking offline reading for already-cached notices.
- Introducing race conditions when switching between batches or accounts.

---

## 3. Constraints

- **Free-Tier Zero Added Compute**: Background sync must adhere strictly to Stale-While-Revalidate without aggressive polling or extraneous RPCs.
- **Strict User & Batch Isolation**: Cached notices must remain partitioned by both `userId` and `batchId` (`notices_cache_${userId}_${batchId}.json`) to prevent cross-account or cross-batch data/reaction leakage.
- **Backward Compatibility**: Existing notice features (PDF/image previews, translation, polls, comments, speed-dial actions) must function with 100% feature parity.

---

## 4. Approaches Considered

### Approach A: High-Performance Architecture Overhaul & Multi-Tier Cache (Selected)
- **ViewHolder Caching**: A static `NoticeViewHolder` caching all child view references.
- **Background Pre-computation**: Parse dates, extract `[assessment_id:]` tags, and format clean text on `Dispatchers.Default` before passing to the UI.
- **Payload DiffUtil**: Update single elements (likes, reads, poll votes, translated text) via payloads.
- **Dedicated Structured Cache**: Decouple notice caching from SharedPreferences into an asynchronous, indexed disk store.

### Approach B: In-Place View Tag Optimization (Rejected)
- Kept raw `JSONObject` and SharedPreferences, caching `findViewById` with tags.
- *Reason for rejection*: Does not eliminate GC pauses caused by heavy JSON serialization in DiffUtil and still hits disk I/O bottlenecks.

### Approach C: Jetpack Compose LazyColumn Rewrite (Rejected)
- Rewrote the entire feed in Compose.
- *Reason for rejection*: High risk of introducing bugs in complex image grids, and animated poll interactions right before release.

---

## 5. Technical Architecture & Component Details

### 5.1 Heterogeneous Feed Hierarchy
The Notices list retains its clean heterogeneous structure:
```kotlin
sealed interface NoticeFeedRow {
    val stableKey: String
    
    data class DateHeader(
        val title: String,
        val isFirst: Boolean
    ) : NoticeFeedRow {
        override val stableKey: String = "header:$title"
    }

    data class NoticeCard(
        val model: NoticePresentationModel
    ) : NoticeFeedRow {
        override val stableKey: String = "notice:${model.id}"
    }

    data class PagingFooter(
        val isLoading: Boolean,
        val hasError: Boolean
    ) : NoticeFeedRow {
        override val stableKey: String = "paging"
    }
}
```

### 5.2 Complete Presentation Model
Transform raw network/cache records into an immutable, pre-computed `NoticePresentationModel` on background threads (`Dispatchers.Default`):
```kotlin
data class NoticePresentationModel(
    val id: String,
    val title: String,
    val cleanBody: CharSequence,
    val rawBody: String,
    val publishedAtText: String,
    val isResource: Boolean,
    val isCancellation: Boolean,
    val isPoll: Boolean,
    val assessmentId: String?,
    val isReadByMe: Boolean,
    val readCount: Int,
    val readerAvatars: List<String>,
    val reactionCount: Int,
    val isLikedByMe: Boolean,
    val commentsCount: Int,
    val isPinned: Boolean,
    val translationText: String?,
    val isTranslating: Boolean,
    val isExpanded: Boolean,
    val poll: PollPresentationModel?,
    val attachments: List<NoticeAttachmentItem>
)

data class PollPresentationModel(
    val id: String,
    val question: String,
    val isMultipleChoice: Boolean,
    val isClosed: Boolean,
    val totalVoters: Int,
    val totalVotes: Int,
    val mySelectedOptionIds: Set<String>,
    val options: List<PollOptionPresentationModel>
)

data class PollOptionPresentationModel(
    val id: String,
    val text: String,
    val votesCount: Int,
    val percentage: Float,
    val isSelectedByMe: Boolean
)
```
- All regexes (`[assessment_id:...]`, URL patterns) and date conversions run once during background mapping, never in `onBindViewHolder`.

### 5.3 View Holder Pattern & Payload Diffing
- `NoticeModernViewHolder` caches all 30+ child view references upon creation.
- `DiffUtil.ItemCallback<NoticeFeedRow>` implements `getChangePayload(oldItem, newItem)`:
  - `PAYLOAD_LIKE`: Updates like icon tint and counter TextView only.
  - `PAYLOAD_READ`: Updates read checkmark and reader avatar row only.
  - `PAYLOAD_POLL_VOTE`: Animates poll progress bars and vote counts without flashing the card.
  - `PAYLOAD_TRANSLATE`: Toggles translated text.
  - `PAYLOAD_EXPAND`: Toggles "see more / see less".
- View change animations disabled: `(recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false` to eliminate item blinks.

### 5.4 Multi-Tier Caching System & SWR Pipeline
- **Tier 1 (Memory L1)**: In-memory list `List<NoticeFeedRow>` kept in `ClassMateAcademicScreensSupabase`.
- **Tier 2 (Disk L2)**: Dedicated app-private JSON file `notices_cache_${userId}_${batchId}.json` in `context.filesDir/cache/`.
  - Stored structure: JSON containing base notices + details map (authors, engagement, reads, polls, attachments).
  - Purged on user logout / account switch (`clearSession`).
- **Two-Step Network SWR Pipeline**:
  1. **Instant L1/L2 Render**: Load L2 from disk on background dispatcher, map to `NoticeFeedRow`, and submit to adapter (`< 20ms`).
  2. **Atomic Background Fetch**:
     - Fetch base `rows("notices", ...)` for the active page.
     - Immediately fetch secondary `rpc("notice_feed_details", ...)` for the new items.
     - **Reconcile with existing older notices**: Merge fresh page with already loaded older notices, deduplicated by `id`.
     - Atomically pre-compute the updated `NoticePresentationModel`s and submit via `submitList`.
     - Write updated snapshot to Tier 2 disk cache asynchronously on `Dispatchers.IO`.

---

## 6. Verification Plan

1. **Scroll Performance & Frame Drops**:
   - Profile via `adb shell dumpsys gfxinfo com.shuaib.classmate`. Verify zero jank frames during rapid flings.
2. **Instant Cache Startup**:
   - Turn off device network (Airplane mode) and open the Notices tab; verify all previously seen notices render immediately with full metadata, images, and attachments.
3. **Payload Diffing Verification**:
   - Tapping "Like" or voting on a poll updates only the target widget without re-animating or flickering the rest of the card.
4. **Regression Check**:
   - Verify image attachment grids, PDF opening, Poll vote casting, Assessment detail bottom sheet opening, and Translation all work without regression.
