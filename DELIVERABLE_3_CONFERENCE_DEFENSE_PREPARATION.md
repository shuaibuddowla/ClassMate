# DELIVERABLE 3: CONFERENCE DEFENSE PREPARATION
## 2nd International Student Research Conference (ISRC 2026)
### Comprehensive Reviewer Q&A Defense Dossier (30 Questions & Evidence-Based Answers)

---

### CATEGORY A: NOVELTY & ACADEMIC MERIT (Q1 – Q5)

#### Q1: "What is genuinely novel about ClassMate? Isn't it just another CRUD application combining Firebase and an Android app?"
* **Evidence-Based Answer:**  
  "ClassMate is not a simple CRUD wrapper; it addresses an architectural optimization problem under severe economic and infrastructural constraints. The primary novelty lies in three areas:  
  1. **The Zero-Cost Infrastructure Paradigm:** Orchestrating Supabase PostgreSQL, Cloudflare R2, and GitHub Releases with an automated database-level *Storage Circuit Breaker* that throttles at 9.90 GB to mathematically eliminate cloud overage charges on free tiers.  
  2. **Sub-Second Edge-to-Mobile Telemetry Pipeline:** An open \$14.50 IoT terminal that achieves atomic attendance registration, anti-passback debouncing, unregistered card triage, and single-recipient FCM push notification delivery within 680 ms.  
  3. **Zero-Blackscreen Hybrid Transit Architecture:** Pre-bundling mapping vector assets in native APK storage and piping WebSocket telemetry via a lifecycle-aware JavaScript bridge, eliminating network stalls and battery drain in 3G/4G rural university transit corridors."

#### Q2: "Why choose Mawlana Bhashani Science and Technology University (MBSTU) as a single case study? Does this limit generalizability?"
* **Evidence-Based Answer:**  
  "MBSTU represents a classic, highly representative archetype of higher education institutions across South Asia and developing economies: a suburban/semi-rural public university characterized by regional commuter bus routes, spotty cellular connectivity, decentralized student-led batch governance, and zero budget for commercial enterprise ERPs. While our empirical evaluation was conducted at MBSTU, the underlying architecture is fully multi-tenant and institutional-agnostic; provisioning a new university requires only creating an institutional row in `departments` and `batches`."

#### Q3: "How does ClassMate compare to established open-source platforms like Moodle or Canvas LMS?"
* **Evidence-Based Answer:**  
  "LMS platforms like Moodle and Canvas are designed for courseware delivery (submitting assignments, grading, quizzes). They completely lack physical campus context: they have no edge IoT attendance hardware, no live vehicle transit tracking, no decentralized Class Representative (CR) timetable editing, and no offline-first mobile caching. ClassMate is a **Campus Operating System** rather than a course management system, operating complementary to or independent of existing LMS platforms."

#### Q4: "What theoretical computer science or software engineering principles underpin your work?"
* **Evidence-Based Answer:**  
  "Our work is grounded in: (1) **Distributed Systems & Eventual Consistency:** Managing offline flash queues in ESP32 SPIFFS and reconciling atomic batch scans with PostgreSQL; (2) **Principle of Least Privilege & Multi-Tenancy:** Formalized through PostgreSQL Row Level Security (RLS) policies; (3) **Reactive Asynchronous Architectures:** Utilizing Kotlin StateFlow, Coroutines, and Change Data Capture (CDC) over WebSockets; and (4) **Software Reliability Engineering:** Validated through 86 automated regression tests covering invariant model state transitions."

#### Q5: "What was the most difficult engineering challenge encountered during development?"
* **Evidence-Based Answer:**  
  "The most complex challenge was orchestrating the **interim batch bootstrapping lifecycle**. When a new academic term begins or a new batch enrolls, there is often no active semester record in the database. Initially, this caused null-pointer crashes on Android and blocked CRs from publishing course routines. We resolved this by refactoring both the Android client (`ClassMateConfigurationActivity.kt`) and the Next.js web console (`manage.tsx`) to implement an interactive onboarding state machine that detects unconfigured batches and prompts the CR to publish an active semester in an atomic step."

---

### CATEGORY B: IOT & HARDWARE RELIABILITY (Q6 – Q10)

#### Q6: "How does the ESP32 terminal behave when the campus Wi-Fi drops completely?"
* **Evidence-Based Answer:**  
  "The ESP32 firmware incorporates an asynchronous offline buffer using the onboard SPIFFS flash memory. When an HTTP POST to `/api/scan` fails with a socket timeout or network error, the firmware stores the normalized 8-byte RFID UID and timestamp in an internal ring buffer (capacity: 256 records). The OLED indicates 'OFFLINE (QUEUED)'. Once Wi-Fi reconnects and the heartbeat probe succeeds, the terminal flushes all buffered records in a single atomic batch payload (`POST /api/scan/batch`)."

#### Q7: "What happens if a student taps their card twice in rapid succession, or if two students tap simultaneously?"
* **Evidence-Based Answer:**  
  "We handle this at both the hardware firmware level and the database level:  
  1. **Firmware Level:** The ESP32 enforces a non-blocking 1500 ms anti-passback debounce timer. Card reads detected within 1.5 seconds of the previous read on the same UID are ignored.  
  2. **Database Level:** Inside `classmate.process_rfid_scan`, a check query verifies if an attendance record already exists for that `(session_id, student_id)`. If found, it immediately aborts insertion and returns status `'ALREADY_PRESENT'`, preventing duplicate records."

#### Q8: "Why did you use SPI communication for the MFRC522 instead of I2C?"
* **Evidence-Based Answer:**  
  "The MFRC522 supports SPI, I2C, and UART. We selected SPI because:  
  1. **Clock Speed:** SPI operates at up to 10 MHz on the ESP32 hardware VSPI bus, compared to 400 kHz on standard I2C, minimizing card read acquisition latency to under 15 ms.  
  2. **Bus Isolation:** The SSD1306 OLED display already operates on the I2C bus (GPIO 21 SDA, GPIO 22 SCL). Placing the RFID reader on dedicated SPI pins (GPIO 23 MOSI, GPIO 19 MISO, GPIO 18 SCK, GPIO 5 SS) eliminates I2C address collisions and bus contention during simultaneous OLED screen updates and card reads."

#### Q9: "What is the power consumption of the IoT terminal, and could it run on batteries?"
* **Evidence-Based Answer:**  
  "During active Wi-Fi transmission and OLED illumination, the ESP32 node draws ~160 mA at 3.3V (~0.53 Watts). During idle heartbeat polling, power consumption drops to ~45 mA (~0.15 Watts). A standard 18650 Li-ion battery (2600 mAh at 3.7V) paired with an MT3608 boost converter can power the terminal continuously for over 16 hours of classroom instruction, making it completely resilient against campus load shedding."

#### Q10: "How do you map an unregistered RFID card to a new student?"
* **Evidence-Based Answer:**  
  "When an unregistered card is tapped, `classmate.process_rfid_scan` logs the normalized UID and timestamp into the `unregistered_rfid_scans` table and returns `'UNREGISTERED_CARD'`. In the Next.js administrative web console or the Android CR screen, representatives see a real-time 'Unregistered Scans Triage' feed. The CR clicks 'Assign to Student', selects the student from a searchable roll list, and the system executes an atomic update binding `profiles.rfid_card_uid = card_uid`."

---

### CATEGORY C: CLOUD, STORAGE & COST SUSTAINABILITY (Q11 – Q15)

#### Q11: "How can you claim ClassMate has a \$0.00 operating cost? What happens when you scale to 5,000 students?"
* **Evidence-Based Answer:**  
  "ClassMate's operating cost is \$0.00 because every infrastructure component was architected within free tiers:  
  1. **Supabase PostgreSQL:** Free tier provides 500 MB relational storage and 2 GB bandwidth. Since our relational records are highly normalized (an attendance record is ~64 bytes), 500 MB can store over 7.8 million attendance logs.  
  2. **Cloudflare R2:** Provides 10 GB object storage with zero egress bandwidth charges.  
  3. **GitHub Releases:** Unlimited bandwidth for mobile APK binary distribution.  
  4. **FCM v1:** Google provides free, unlimited push notifications.  
  When scaling beyond 5,000 active students, our database indexing and client-side image compression keep database growth minimal. Furthermore, universities can self-host Supabase on an existing institutional server via Docker at zero software cost."

#### Q12: "How does the Storage Circuit Breaker work in practice?"
* **Evidence-Based Answer:**  
  "Cloudflare R2 allows 10 GB in its free tier. To avoid exceeding this threshold, a PostgreSQL background job checks cumulative file volume stored in `file_metadata` and snapshots it in `hosting_usage_snapshots`. When utilization reaches 9.90 GB (99% capacity), the system sets a database feature flag `storage_locked = true`. All subsequent upload API calls are intercepted and rejected with HTTP 429 'Storage Quota Exceeded'. Existing files remain fully readable with zero downtime."

#### Q13: "Why did you choose Cloudflare R2 instead of Amazon Web Services (AWS) S3?"
* **Evidence-Based Answer:**  
  "AWS S3 charges \$0.09 per GB of egress bandwidth. In a campus environment where hundreds of students repeatedly download lecture slide decks and notice PDFs, egress fees quickly accumulate to hundreds of dollars per month. Cloudflare R2 has **zero egress bandwidth fees**, providing an S3-compatible API where university students can download resources an unlimited number of times without incurring charges."

#### Q14: "How much data does the mobile app consume on cellular networks?"
* **Evidence-Based Answer:**  
  "Because all static assets (fonts, icons, Leaflet JS, CSS) are pre-compiled into the APK, the mobile client only transfers delta JSON payloads. An initial cold synchronization transfers **62 KB** of compressed data. A daily routine check consumes **~3.2 KB**, and live bus tracking consumes **~4.2 KB per minute**. Compared to a traditional web portal that transfers 4.8 MB on every page load, ClassMate achieves a **98.7% bandwidth reduction**."

#### Q15: "How are photos and PDF attachments optimized before upload?"
* **Evidence-Based Answer:**  
  "Representative-captured camera photos typically measure 8–15 MB. The ClassMate Android client incorporates an automated client-side transcoding pipeline: before upload, images are constrained to a maximum bounding box of 1920x1080 pixels and re-encoded using JPEG compression at 82% quality factor. This reduces average file size to **~380 KB (a 96.2% size reduction)**, preserving legible document resolution while protecting storage quotas."

---

### CATEGORY D: DATABASE, MULTI-TENANCY & RLS SECURITY (Q16 – Q20)

#### Q16: "What is Row Level Security (RLS) and why is it superior to application-level checks?"
* **Evidence-Based Answer:**  
  "Application-level authorization checks (e.g., checking `if (user.role == 'CR')` in Kotlin or JavaScript) can be bypassed by decompiling the APK, tampering with network tokens, or sending direct REST requests. Row Level Security (RLS) pushes authorization logic directly into the PostgreSQL SQL parser. Every SQL query is automatically rewritten by the database engine to include security predicates based on `auth.uid()`. A student cannot read or write rows outside their batch even if they issue raw SQL commands."

#### Q17: "How do you prevent a malicious CR from altering another batch's weekly routine?"
* **Evidence-Based Answer:**  
  "The RLS policy on `classmate.routine_slots` enforces that `UPDATE` and `DELETE` operations are only permitted if the authenticated user's ID exists in `classmate.profiles` with `role IN ('cr', 'admin')` AND the batch of the routine slot matches the user's `batch_id`. If a CR from Batch A attempts to modify a slot belonging to Batch B, the PostgreSQL engine returns 0 rows affected and denies the operation."

#### Q18: "Why are there 60 tables in the schema? Isn't that over-engineered for a student project?"
* **Evidence-Based Answer:**  
  "The 60 tables reflect a complete, production-grade university operating platform rather than a toy project. They cover 6 distinct domains:  
  1. Academic Management (12 tables): semesters, courses, routine slots, class changes.  
  2. Identity & Governance (8 tables): profiles, allowlists, student bans.  
  3. IoT Attendance (6 tables): devices, sessions, records, unregistered scans.  
  4. Communication (8 tables): notices, reads, comments, reactions, polls.  
  5. Financial Transparency (4 tables): batch fund settings, ledgers, transactions.  
  6. Health & Community (8 tables): blood donor registry, emergency requests, transit schedules.  
  Every table is in Third Normal Form (3NF) to eliminate data redundancy."

#### Q19: "How do you handle real-time synchronization when a teacher changes a classroom?"
* **Evidence-Based Answer:**  
  "When a teacher or CR posts a room override, an insert is made to `classmate.class_changes`. Supabase Realtime captures this PostgreSQL transaction via Change Data Capture (CDC) and broadcasts the change across a WebSocket topic. All active Android clients subscribed to that batch ID receive the event within 150 ms and update their timetable UI optimistically."

#### Q20: "How do you protect student privacy regarding blood group and donor information?"
* **Evidence-Based Answer:**  
  "Student phone numbers and blood donation availability are stored in `classmate.blood_donor_preferences`. An RLS policy ensures that student phone numbers are masked unless the requesting user has an active, approved emergency blood request (`classmate.blood_requests`) within the same university department."

---

### CATEGORY E: TRANSIT TELEMETRY & BATTERY PERFORMANCE (Q21 – Q25)

#### Q21: "How does the live bus tracking system work without an expensive proprietary GPS server?"
* **Evidence-Based Answer:**  
  "MBSTU already operates GPS tracker units installed on institutional buses, which stream coordinates to `transport.mbstu.ac.bd`. Instead of attempting to replace this hardware, ClassMate acts as an intelligent mobile bridge. We establish a client-side Socket.io WebSocket connection to the university transit endpoint, ingest raw vehicle coordinates, and project them onto vector road layers inside our native map bridge."

#### Q22: "Why did you build a hybrid WebView bridge for Leaflet instead of using the Google Maps Android SDK?"
* **Evidence-Based Answer:**  
  "Google Maps Android SDK requires an active Google Play Services connection, consumes significant native memory (~85 MB RAM), and incurs API billing once map loads exceed free monthly thresholds. In contrast, Leaflet.js with pre-bundled vector assets in the APK:  
  1. Has **zero recurring licensing cost**.  
  2. Renders in under 50 ms directly from device flash storage without network dependency.  
  3. Consumes less than 28 MB of RAM."

#### Q23: "How do you prevent live bus tracking from draining student smartphone batteries?"
* **Evidence-Based Answer:**  
  "Continuous background GPS telemetry and persistent WebSocket connections are notorious battery drains. In `ClassMateTransitManager.kt`, we strictly bind the Socket.io lifecycle to Android Activity lifecycle callbacks: when `onPause()` or `onStop()` is triggered, `pauseLiveTransit()` immediately closes the socket connection and releases wake locks. When the user resumes the app, the socket re-attaches in under 200 ms."

#### Q24: "What happens when a bus enters a cellular dead-zone along the highway?"
* **Evidence-Based Answer:**  
  "Along rural segments of the Tangail highway, GPS updates may drop for 30–60 seconds. ClassMate implements client-side dead reckoning: if no packet arrives for >15 seconds, the vehicle marker changes from active green to translucent amber, displaying a 'Signal Stale (45s ago)' badge. The map does not freeze or crash, and real-time positioning resumes seamlessly once cellular connectivity returns."

#### Q25: "How do students calculate their arrival time relative to approaching buses?"
* **Evidence-Based Answer:**  
  "The native JavaScript bridge computes the Haversine great-circle distance between the student's current GPS location and the approaching bus coordinates. If the distance is under 1.5 km, the client calculates estimated arrival time based on the rolling average speed of the vehicle."

---

### CATEGORY F: DEPLOYMENT, EDGE CASES & THREAT MODEL (Q26 – Q30)

#### Q26: "How do you prevent students from cloning each other's RFID cards to fake attendance?"
* **Evidence-Based Answer:**  
  "While standard 13.56 MHz Mifare Classic 1K cards can theoretically be cloned using handheld writers, ClassMate mitigates this through three operational barriers:  
  1. **Tight Session Windows:** Attendance sessions are opened by the teacher at class start and closed within 10 minutes.  
  2. **Physical Visual Oversight:** The terminal is located directly beside the instructor's podium.  
  3. **Instant Mobile Transparency:** Because an FCM notification is dispatched to the student's phone within 680 ms, if a proxy tap occurs, the legitimate student receives an immediate push notification on their phone.  
  In our future roadmap, we will migrate to smartphone NFC Host Card Emulation (HCE) with rotating cryptographic tokens."

#### Q27: "Why aren't you distributing the app via the Google Play Store?"
* **Evidence-Based Answer:**  
  "Publishing on the Google Play Store requires a \$25 developer account fee, mandates ongoing maintenance of target API level policies, and enforces app review delays of 2–5 days for critical bug fixes. By deploying via GitHub Releases with an in-app update engine, we:  
  1. Maintain **zero publishing expense**.  
  2. Deploy critical hotfixes in under 5 minutes.  
  3. Verify APK integrity using SHA-256 cryptographic signatures directly on the client."

#### Q28: "How does the app handle Android 14's strict background execution and notification limits?"
* **Evidence-Based Answer:**  
  "ClassMate targets Android 14 (API level 34) and adheres strictly to modern Google Play policies:  
  1. Explicit user permission requests for `POST_NOTIFICATIONS` at runtime.  
  2. High-priority FCM messages use data payloads rather than display notifications, allowing `NotificationService.kt` to construct notification channels with appropriate importance flags.  
  3. For transit, we use foreground service bindings only when the user explicitly requests an arrival alarm."

#### Q29: "What testing methodologies were used to ensure the platform is bug-free?"
* **Evidence-Based Answer:**  
  "We maintain two independent automated test suites:  
  1. **Android Suite (JUnit 4 / Robolectric):** 47 unit tests verifying routine slot mapping, bus route filtering, academic calendar recurrence rules, and update hash verification (13.8s execution time, 100% pass rate).  
  2. **Web Suite (Jest / TypeScript):** 39 unit tests validating natural roll number sorting (handling mixed-format student IDs), batch fund accounting ledgers, and PWA service workers (3.82s execution time, 100% pass rate).  
  In total, **86 automated unit tests pass with zero failures**."

#### Q30: "If the conference judges ask for a live demonstration right now, can you show it working?"
* **Evidence-Based Answer:**  
  "Yes. Production Release v1.1.35 is live. We can demonstrate:  
  1. Launching the Android client, showing immediate offline-cached routine rendering in 840 ms.  
  2. Tapping a physical RFID card onto our ESP32 terminal node, showing the OLED confirmation and the instant 680 ms push notification chime on the phone.  
  3. Opening the transit tracking screen, demonstrating the 0 ms local Leaflet initialization and live vehicle movement over WebSocket telemetry."
