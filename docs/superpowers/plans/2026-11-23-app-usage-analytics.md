# App Usage Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement page and screen usage analytics using Firebase Analytics with zero database/compute load on Supabase.

**Architecture:** Integrate the `firebase-analytics-ktx` SDK using the existing Firebase BOM. Create a singleton `ClassMateAnalytics` helper that safely handles screen tracking and user batch metadata in the background. Trigger screen view events across all home tabs and modal sheets.

**Tech Stack:** Kotlin, Android Jetpack, Firebase BOM 32.7.4, Firebase Analytics KTX

**Spec:** [`docs/superpowers/specs/2026-11-23-app-usage-analytics-design.md`](file:///D:/Code%20&%20Scripts/ClassMate-main/docs/superpowers/specs/2026-11-23-app-usage-analytics-design.md)

## Global Constraints
* Zero database calls or writes to Supabase for tracking.
* Non-blocking: all analytics calls must be asynchronous and never impede 60/120fps UI rendering or the 0–16ms tab switching.
* Must use Firebase BOM version `32.7.4` already configured in `libs.versions.toml`.

## Review Focus
1. **Network offline behavior**: Analytics must queue locally and not fail or throw exceptions when the user is offline.
2. **Tab switching latency**: Calling `logScreen` must take < 1ms on the main thread.
3. **App launch stability**: `ClassMateAnalytics` must initialize gracefully without crashing if Firebase has not yet initialized.
4. **PII privacy**: Only screen names (e.g. `Timetable`, `Notices`, `UpcomingAssessments`) and batch label identifiers are sent; no personal credentials, message contents, or phone numbers.
5. **DebugView verification**: Developers must be able to verify live event streams via ADB shell debug flags.

---

### Task 1: Add Firebase Analytics Dependency
**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] Add `firebase-analytics = { group = "com.google.firebase", name = "firebase-analytics-ktx" }` to `[libraries]` in `gradle/libs.versions.toml`.
- [ ] Add `implementation(libs.firebase.analytics)` to `dependencies` block in `app/build.gradle.kts`.
- [ ] Run `./gradlew dependencies --configuration debugCompileClasspath` or `./gradlew compileDebugKotlin` to verify dependency resolution.

---

### Task 2: Create `ClassMateAnalytics` Singleton
**Files:**
- Create: `app/src/main/java/com/shuaib/classmate/utils/ClassMateAnalytics.kt`

- [ ] Create `object ClassMateAnalytics`:
  - Lazy initialization of `FirebaseAnalytics` instance via `Firebase.analytics`.
  - Method `logScreen(screenName: String, screenClass: String = "MainActivity")` logging `FirebaseAnalytics.Event.SCREEN_VIEW` with `FirebaseAnalytics.Param.SCREEN_NAME` and `FirebaseAnalytics.Param.SCREEN_CLASS`.
  - Method `setUserBatch(batchLabel: String)` logging user property `"selected_batch"`.
  - Method `logEvent(eventName: String, params: Map<String, Any?> = emptyMap())` for key actions (e.g. notice read, assessment created).
  - Wrap internal calls in `runCatching` to guarantee it never crashes the application.

---

### Task 3: Instrument Screen View Tracking
**Files:**
- Modify: `app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthActivity.kt`
- Modify: `app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt`

- [ ] In `ClassMateAuthActivity.renderHomeTab(tab: Int)`:
  - Map `tab` ID to screen name:
    - `R.id.nav_timetable` -> `"Timetable"`
    - `R.id.nav_notices` -> `"Notices"`
    - `R.id.nav_upcoming` -> `"UpcomingAssessments"`
    - `R.id.nav_pdf` -> `"CoursesLibrary"`
    - `R.id.nav_transit` -> `"TransitTracker"`
    - `R.id.nav_friends` -> `"Batchmates"`
    - `R.id.nav_profile` -> `"Profile"`
  - Call `ClassMateAnalytics.logScreen(screenName)`.
- [ ] In `ClassMateAuthActivity`: when `selectedBatchLabel` changes or is loaded, call `ClassMateAnalytics.setUserBatch(selectedBatchLabel)`.
- [ ] In `ClassMateAcademicScreensSupabase`:
  - When direct notice view opens (`openDirectNotice` or notice card click): call `ClassMateAnalytics.logScreen("NoticeDetail", "NoticeReaderSheet")`.
  - When assessment creator opens (`openPostAssessmentSheet`): call `ClassMateAnalytics.logScreen("PostAssessment", "AssessmentSheet")`.
  - When period companion bottom sheet opens: call `ClassMateAnalytics.logScreen("PeriodCompanion", "PeriodSheet")`.

---

### Task 4: Compilation, Deployment & Verification
**Files:**
- Verification only

- [ ] Run `./gradlew assembleDebug` to ensure error-free compilation.
- [ ] Install APK on the connected device (`192.168.0.183:34925`).
- [ ] Enable Firebase Analytics debug mode via adb:
  `adb shell setprop debug.firebase.analytics.app com.shuaib.classmate`
- [ ] Exercise the app tabs and sheets on the phone.
- [ ] Inspect logcat (`adb logcat -d -s FA:V FA-SVC:V`) to confirm `Logging event: screen_view` events are firing with correct screen names.
- [ ] Record findings and complete walkthrough document.
