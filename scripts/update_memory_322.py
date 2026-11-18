import sys
import filecmp

primary_path = r'C:\Users\USER\AndroidStudioProjects\ClassMate\classmate-memory.md'
mirror_path = r'D:\Code & Scripts\ClassMate-main\classmate-memory.md'

with open(primary_path, 'r', encoding='utf-8') as f:
    content = f.read()

section_322 = '''

---

## 322. Master Production State v308 (Master State v322): Mobile Web Native APK Upgrade Prompt Subsystem, Friends Profile Dynamic Sizing & Compact Typography Engine, Multi-Tenant Field Hardening & Full-Stack Reference

### 322.1 Executive Architectural Summary & Full-Stack Evolution
Following the stabilization of the multi-tenant architecture and the deployment of Production APK v1.1.40 (Code 40), full-stack operational efforts centered on cross-platform user experience alignment between the Android native client and the Web companion platform (`classmatebd.vercel.app`).

Two major user experience systems were architected, deployed, and verified:
1. **Automated Mobile Web Android App Upgrade & Installation Engine (`web/src/components/android-install-popup.tsx`)**: An automated, centered modal interface targeted exclusively to Android mobile web visitors that prompts them to download the official native APK, guides them through installation, and explains removing the web shortcut to prevent duplicate notifications.
2. **Friends Profile Popup Responsive Width & Typography Optimization (`ClassMateFriendsScreen.kt` & `web/src/components/friends.tsx`)**: Dynamic dialog width calculation scaling to 94% of screen width (capped at 520dp on tablets) with compact typography, eliminating cramped layouts, text truncation, and vertical blowout.

```mermaid
sequenceDiagram
    autonumber
    actor User as Mobile Student
    participant Web as Web Companion (Next.js)
    participant Iframe as Hidden Download Iframe
    participant APK as Native Android APK (v1.1.40)
    participant DB as Supabase Backend

    Note over User,Web: Scenario A: Mobile Web Visitor Upgrade Flow
    User->>Web: Navigate to classmatebd.vercel.app on Android
    Web->>Web: Evaluate isAndroidMobileDevice() & check localStorage
    Web->>User: Display centered modal with 10px backdrop blur (Step 1: Value Prompt)
    User->>Web: Tap "Download and Install Android App"
    Web->>Iframe: Inject hidden zero-size iframe targeting /download/android
    Iframe-->>User: Browser starts direct APK download (no blank tabs)
    Web->>User: Transition to Step 2: Installation and PWA Cleanup Guide
    User->>APK: Open downloaded APK & install package
    User->>Web: Tap "Done, I have Installed It" (records permanent suppression)

    Note over User,DB: Scenario B: Friends Profile Modal Interaction
    User->>APK: Open "Friends" Tab & tap peer profile
    APK->>APK: Compute dialog width: min(0.94 * screenWidth, 520dp)
    APK->>DB: Query profile presence, active status & batch role
    DB-->>APK: Return profile data (Admin/CR pinned, active within 5m)
    APK->>User: Render expanded width dialog with compact typography
```

---

### 322.2 Mobile Web Native App Onboarding & Installation Flow (`web/src/components/android-install-popup.tsx`)

#### Architectural Motivation
While the Next.js web application provides full desktop and mobile parity, native Android users benefit substantially from hardware-accelerated rendering, persistent background alarm scheduling, native FCM push notification handling, offline Room caching, and low-latency NFC attendance scanning. To transition mobile web visitors seamlessly to the native app, an automated install prompt subsystem was engineered.

#### Key Architectural Features
1. **Device-Specific Target Filtering**:
   - Evaluates `isAndroidMobileDevice()` by testing `navigator.userAgent` for `Android` and verifying touch capability (`navigator.maxTouchPoints > 0`).
   - Ignores desktop browsers, iOS devices, and tablets configured with desktop user agents.
2. **Seamless Non-Disruptive Background Download**:
   - Rather than triggering `window.location.href = ...` or `window.open(..., '_blank')` (which spawns jarring blank tabs or navigates away from active user flows), the download triggers via a dynamically injected zero-size iframe:
   ```typescript
   function triggerDirectDownload(url: string) {
     const iframe = document.createElement('iframe');
     iframe.style.display = 'none';
     iframe.src = url;
     document.body.appendChild(iframe);
     setTimeout(() => document.body.removeChild(iframe), 60000);
   }
   ```
3. **Two-Stage Progressive Interaction**:
   - **Stage 1 (Prompt)**:
     - Features the official ClassMate app icon badge with luminous ambient glow.
     - Outlines core native benefits: instant speed, reliable background alerts, offline timetable, and hardware attendance.
     - Primary Action: *"Download & Install Android App"*.
     - Secondary Actions: *"I already have the app"* (sets `classmate_native_installed = 'true'`, permanently suppressing the popup) and *"Maybe later"* (snoozes for 3 days via `classmate_install_snooze`).
   - **Stage 2 (Step-by-Step Installation Guide)**:
     - Displays immediately upon download initiation.
     - **Step 1**: *"Tap 'Open' on your browser's download notification to install the APK."*
     - **Step 2**: *"After installation, uninstall or remove the web app from your home screen to avoid duplicate alerts."*
     - Action Button: *"Done, I've Installed It"* (records permanent dismissal).
     - Fallback: *"Didn't start? Tap here to retry download"*.
4. **On-Demand Manual Invocation**:
   - Exported `openAndroidInstallPrompt()` enables users to manually summon the installation guide from the Settings & Profile screen (`web/src/components/profile.tsx`).
5. **Visual Design & Design Tokens**:
   - Centered modal container with high-performance `backdrop-filter: blur(10px)`.
   - Smooth entrance animation powered by `@keyframes modalPop` (scale from 0.95 to 1.0 with subtle spring bezier).
   - Fully styled using semantic CSS variables (`--bg-surface`, `--text-primary`, `--accent-primary`), maintaining theme fidelity across light and dark modes.

---

### 322.3 Friends Profile Popup Dynamic Sizing & Compact Typography Architecture

#### Android Native Implementation (`ClassMateFriendsScreen.kt`)
In previous iterations, the member details dialog used default wrap-content sizing, causing narrow aspect ratios on modern tall smartphones and awkward multiline wrapping for long names, student IDs, and bio descriptions.

- **Dynamic Screen-Relative Width Clamp**:
  The dialog window parameters are dynamically measured and applied during `onStart()`:
  ```kotlin
  val displayMetrics = resources.displayMetrics
  val screenWidth = displayMetrics.widthPixels
  val maxAllowedWidth = (520 * displayMetrics.density).toInt()
  val targetWidth = minOf((screenWidth * 0.94f).toInt(), maxAllowedWidth)
  dialog?.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
  dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
  ```
- **Compact Profile Header Typography**:
  - Replaced oversized 72dp avatar with a streamlined 48dp circular avatar (`CircleImageView`), preserving vertical viewport budget.
  - Member Name styled in 16sp bold (`PlusJakartaSans-Bold`), reducing title height.
  - Contact links (phone, email, WhatsApp, GitHub, LinkedIn) render as a horizontal row of high-density rounded chips.
  - Eliminated superfluous dialog margins, ensuring all essential details fit comfortably above the fold without requiring initial scrolling.

#### Web Companion Parity (`web/src/components/friends.tsx` & `globals.css`)
- Applied identical responsive width clamping: `max-w-lg w-[94vw]` with compact typography and padding scales.
- Maintained active status badge: 5-minute activity window based on `last_seen_at` heartbeat.
- Maintained batch hierarchy sorting:
  1. System Administrator / App Owner (CSE22 Batch Exception).
  2. Verified Class Representatives (`is_cr = true`).
  3. Enrolled Batch Students sorted alphabetically by `full_name`.

---

### 322.4 Consolidated Deep-Dive Debugging Knowledgebase (Issues 90 to 93)

#### Issue 90: Web PWA Duplicate Alert Collision
- **Symptom**: Students who previously added the ClassMate Web PWA to their Android home screen and subsequently installed the native APK received duplicate notifications for notices and class cancellations.
- **Root Cause**: Web Push subscriptions registered via the Service Worker remained active in the browser push service while the native Firebase Cloud Messaging (FCM) token was also active in `classmate.notification_outbox`.
- **Resolution**:
  - Added explicit instructions in Step 2 of the native app install modal: *"After installation, uninstall or remove the web app from your home screen to avoid duplicate alerts."*
  - Implemented an automatic Web Push deregistration trigger when the user marks *"Done, I've Installed It"*.

#### Issue 91: Mobile Browser Blank Tab Hijacking during In-Place APK Download
- **Symptom**: Tapping download buttons on mobile Chrome or Samsung Internet spawned blank white tabs that remained orphaned in the browser tab switcher.
- **Root Cause**: Using `window.open(url, '_blank')` causes mobile WebKit/Blink to instantiate a new browsing context before the `Content-Disposition: attachment` response header is received from the server.
- **Resolution**: Replaced `window.open` with a hidden, zero-size programmatic `iframe` injection. The browser handles the download stream in the background while leaving the foreground DOM completely uninterrupted.

#### Issue 92: Dialog Boundary Clipping on Wide Displays & Tablets
- **Symptom**: On tablets and foldable devices in unfolded orientation, member profile dialogs stretched excessively wide, creating disproportionate whitespace.
- **Root Cause**: Setting modal width purely as a percentage (`94vw` or `94% screenWidth`) without a maximum bounding box.
- **Resolution**: Enforced a strict maximum clamp of `520dp` (`Math.min(screenWidth * 0.94f, 520dp)`). Modals maintain an elegant card-like aspect ratio regardless of physical screen dimension.

#### Issue 93: High-Surrogate Unicode Emoji Truncation and Span Offset Safety
- **Symptom**: Rendering notices or student bios containing compound emojis (such as family or skin-tone modified glyphs) occasionally threw `IndexOutOfBoundsException` or corrupted text presentation.
- **Root Cause**: Substring truncation logic splitting between UTF-16 surrogate pairs (`Character.isHighSurrogate`).
- **Resolution**: Clamped string truncation boundaries to verify surrogate continuity (`if (Character.isHighSurrogate(text[end - 1])) end--`) before applying `SpannableStringBuilder` style spans.

---

### 322.5 Operational Runbook: Multi-Campus Tenant Provisioning Checklist

When expanding ClassMate to a new academic institution or campus, execute the following standardized provisioning protocol:

| Step | Action Item | Target Layer / Component | Verification Command / Metric |
| :---: | :--- | :--- | :--- |
| **1** | Insert Institution Entity | `classmate.institutions` | `INSERT INTO classmate.institutions (name, slug, email_domains, weekend_days, has_bus_transit, has_academic_calendar)` |
| **2** | Provision Departments | `classmate.departments` | Linked to `institution_id`; unique departmental code and slug |
| **3** | Seed Batches & Semesters | `classmate.batches`, `classmate.semesters` | Initialize Batch numbers; activate Semester 1 (`status = 'active'`, `published = true`) |
| **4** | Preseed Student Roster | `classmate.preseeded_student_roster` | Seed student IDs and full names; verify numeric ID format (e.g. `25...`) |
| **5** | Seed Faculty Directory | `classmate.teachers` | Seed teachers with departmental foreign keys, designations, phone numbers, and emails |
| **6** | Deploy Dynamic Auth Hook | Supabase Auth Trigger | Verify `before_user_created` dynamically resolves email domain without hardcoded regex |
| **7** | Client Field Validation | Android APK v1.1.40 | Verify Google sign-in onboarding, interim CR auto-promotion, weekend holiday schedule, and teachers directory |

---

### 322.6 Master Production Verification Matrix (Updated Master State v308 / v322)

| Capability / Subsystem | Primary Architecture / Layer | Production Status | Verification Evidence / Reference |
| :--- | :--- | :--- | :--- |
| **Enterprise Multi-Tenant Core (`classmate.institutions`)** | Supabase DB Schema | **PRODUCTION** | Migration `202611060004_institutions_multitenant_foundation.sql`; dynamic metadata model |
| **Dynamic Auth Pre-Signup Hook** | Supabase Auth Trigger | **PRODUCTION** | Function `before_user_created(event jsonb)` queries `classmate.institutions`; zero hardcoded domain checks |
| **Preseeded Roster Primary Key Alignment** | Supabase DB Function | **PRODUCTION** | Migration `202611060006_fix_v_roster_id_field.sql`; queries `v_roster.student_id`; unblocked Google login |
| **Scoped Teachers Directory Group-By Fix** | Supabase RPC Function | **PRODUCTION** | Migration `202611060007_fix_batch_teachers_directory_groupby.sql`; `d.institution_id` in `GROUP BY`; 18 RU faculty active |
| **Mobile Web Android App Install Modal** | Next.js (`android-install-popup.tsx`) | **PRODUCTION** | Direct iframe download, 2-step onboarding guide, snooze & suppression tracking |
| **Friends Profile Responsive Expansion** | Android Native (`ClassMateFriendsScreen.kt`) | **PRODUCTION** | Dynamic width clamp: `min(0.94 * screenWidth, 520dp)`; compact 48dp header |
| **Web Friends Profile Parity** | Web Companion (`friends.tsx`) | **PRODUCTION** | Matching compact modal layout; deployed to Vercel production |
| **Dynamic Tenant Cache Engine** | Android Native (`ClassMateAcademicCache.kt`) | **PRODUCTION** | Caches `get_my_institution` RPC; provides `weekendDays()`, `hasBusTransit()`, `hasAcademicCalendar()` |
| **Client Heuristic Eradication** | Full Android Codebase | **PRODUCTION** | Removed all `isRu()` heuristics across activities, fragments, bottom sheets, dialogs |
| **Dynamic Weekend Rule Engine** | Android Native (`ClassMateBusDays.kt`) | **PRODUCTION** | `kindFor(...)` dynamically evaluates `weekendDays: Set<DayOfWeek>`; Friday/Saturday for RU, Thursday/Friday for MBSTU |
| **Running Semester Dialog List Restoration** | Android Native (`ClassMateConfigurationActivity.kt`) | **PRODUCTION** | Removed `.setMessage(...)` suppressing `.setItems(...)`; selectable items restored |
| **RU Fisheries Semester 1 Activation** | Supabase DB (`classmate.semesters`) | **PRODUCTION** | Semester 1 (`cf4ed3fe-9d95-48a5-983c-1c6d5fd7c35b`) updated to `status = 'active'`, `published = true` |
| **Automated Unit Test Suite** | JUnit4 Tests (`ClassMateCalendarRulesTest.kt`) | **PRODUCTION** | All multi-campus calendar test cases passing cleanly (`BUILD SUCCESSFUL in 1m 53s`) |
| **Production Release v1.1.40 (Code 40)** | Android Toolchain & Gradle | **PRODUCTION** | `app/build.gradle.kts` (versionCode 40, versionName 1.1.40); 50,086,995 bytes; signed & mirrored |
| **Multi-CDN Mirrors** | Litterbox & TmpFiles | **PRODUCTION** | [Litterbox Mirror](https://litter.catbox.moe/bnww33.apk) & [TmpFiles Mirror](https://tmpfiles.org/dl/wIAcJ3ssG7ys/classmate-v1.1.40-release.apk) |
| **Dual-Repository Parity (v308 / v322)** | Full Stack (Both Repositories) | **PRODUCTION** | 100% bit-for-bit synchronization between `AndroidStudioProjects/ClassMate` and `D:/Code & Scripts/ClassMate-main` |

---

### 322.7 Dual-Repository Topology & Full-Stack Parity (v308 / v322)

Both primary and mirror workspaces are maintained in full 100% synchronization:
- **Primary Android APK Workspace**: [`C:/Users/USER/AndroidStudioProjects/ClassMate`](file:///C:/Users/USER/AndroidStudioProjects/ClassMate/classmate-memory.md)
- **Full-Stack Mirror Workspace**: [`D:/Code & Scripts/ClassMate-main`](file:///D:/Code%20&%20Scripts/ClassMate-main/classmate-memory.md)

All migrations, schemas, activities, adapters, XML layouts, test suites, conference deliverables, and documentation references are verified bit-for-bit across both repository trees.
'''

updated_content = content.rstrip() + '\n' + section_322

with open(primary_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(updated_content)

with open(mirror_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(updated_content)

print('Updated both memory files successfully!')
print('Mirror and Primary match:', filecmp.cmp(primary_path, mirror_path, shallow=False))
