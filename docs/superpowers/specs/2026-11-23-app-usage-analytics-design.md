# App Usage & Screen Analytics Design

## 1. Intent & The Why
The goal is to understand how students and teachers navigate ClassMate—specifically identifying which pages/screens are visited most frequently (e.g. Timetable vs Notices vs Upcoming vs Courses) and observing user engagement trends without incurring compute, database write IOPS, or server costs on Supabase.

## 2. Goals, Non-Goals, and Anti-Goals
* **Goals**:
  * Track screen views across main navigation tabs (Timetable, Notices, Upcoming Assessments, Courses/Library, Transit, Batch, Profile).
  * Track major high-value sub-screens/sheets (Notice detail reader, Assessment creator, Attendance sheet, Bus tracker).
  * Zero impact on server/database resources (no writes to Supabase).
  * Automated batching, compression, and offline queueing handled by Google Play Services / Firebase SDK.
* **Non-Goals**:
  * Real-time in-app dashboard display (analytics will be viewed in the Google Firebase Console).
  * Tracking personally identifiable sensitive inputs or personal chat messages.
* **Anti-Goals**:
  * No per-screen database calls to Supabase or network locks on the UI thread.
  * No increased battery drain or wake-lock usage on the user's phone.

## 3. Constraints
* The app already includes `google-services.json` and Firebase BOM `32.7.4`.
* Must adhere to Google Play privacy policies: no tracking of sensitive PII without user consent.
* Must not impact the instant (0–16ms) tab-switching performance recently achieved.

## 4. Approaches Considered
1. **Approach A: Custom Supabase Aggregation Table**:
   * Count screen hits in memory on the device and flush a single daily summary to Supabase via an RPC.
   * *Trade-off*: Keeps data in Supabase, but requires authoring migration tables, RPC functions, and building an admin UI to visualize the numbers.
2. **Approach B: Firebase Analytics (Chosen)**:
   * Add `firebase-analytics-ktx` under existing Firebase BOM.
   * Track `SCREEN_VIEW` events and standard screen names.
   * *Trade-off*: Data is viewed in the Firebase Console rather than inside the app, but offers rich out-of-the-box charts (most visited screens, user retention, daily active users, engagement time per screen) at 0 compute cost and 0 database load on Supabase.

## 5. Architecture & Implementation Details
1. **Dependency**:
   * Add `firebase-analytics = { group = "com.google.firebase", name = "firebase-analytics-ktx" }` to `gradle/libs.versions.toml`.
   * Add `implementation(libs.firebase.analytics)` to `app/build.gradle.kts`.
2. **Analytics Helper**:
   * Create a lightweight singleton `ClassMateAnalytics` with methods:
     * `logScreen(screenName: String, screenClass: String = "MainActivity")`
     * `setUserBatch(batchLabel: String)` (User property to allow filtering analytics by department/batch in Firebase).
3. **Integration Points**:
   * In `ClassMateAuthActivity.renderHomeTab(tab: Int)`: Log screen view on tab transition (`Timetable`, `Notices`, `UpcomingAssessments`, `Courses`, `Transit`, `Batch`, `Profile`).
   * In modal sheets / sub-activities: Log relevant screen views (e.g. `NoticeDetail`, `PostAssessment`, `AttendanceDialog`).
4. **Performance Safety**:
   * Firebase Analytics logging is asynchronous and offloaded to background threads by Google Play Services, preserving 60/120fps UI fluidity.
