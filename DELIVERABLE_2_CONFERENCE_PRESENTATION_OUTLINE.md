# DELIVERABLE 2: CONFERENCE PRESENTATION OUTLINE
## 2nd International Student Research Conference (ISRC 2026)
**Organized by:** Dhaka University Research Society (DURS), University of Dhaka  
**Presentation Title:** *ClassMate: Architecture, Implementation, and Empirical Evaluation of an Integrated Low-Cost Smart Campus Operating Platform for Higher Education*  
**Presenter:** Shuaib Ahmed, Dept. of Information and Communication Technology, MBSTU  
**Allocated Time:** 12 Minutes Presentation + 3 Minutes Q&A (Total 15 Minutes)

---

### Slide 1: Title & Academic Context (Time: 0:00 – 1:00)
* **Slide Title:** ClassMate: Integrated Low-Cost Smart Campus Operating Platform for Higher Education
* **Header / Sub-header:** An Evidence-Based Engineering Investigation & Empirical Deployment at Mawlana Bhashani Science and Technology University (MBSTU)
* **Author / Affiliation:** Shuaib Ahmed, Department of ICT, Faculty of Engineering, MBSTU
* **Conference Banner:** 2nd International Student Research Conference (ISRC 2026), Dhaka University Research Society (DURS)
* **Key Visual:** ClassMate official app icon, university crest, and production release badge (`v1.1.35 | 168k LOC | 86 Tests Passed`).
* **Speaker Notes:** 
  > "Good morning, respected judges, faculty members, and fellow researchers. Today, I am proud to present ClassMate—a full-stack, IoT-integrated smart campus operating platform engineered specifically to resolve administrative fragmentation in developing higher education institutions under strict zero-cost infrastructure constraints."

---

### Slide 2: The Multi-Channel Communication Crisis (Time: 1:00 – 2:15)
* **Slide Title:** The Problem: Administrative Fragmentation & The ERP Impasse
* **Talking Points:**
  * **The Fragmented Reality:** Student communication is scattered across WhatsApp groups, Facebook pages, bulletin boards, and unorganized Google Drives.
  * **Critical Failure Modes:** High noise-to-signal ratio, lack of timetable version control, paper attendance proxy signatures, and commuting blackout for campus transit.
  * **The Economic Barrier:** Enterprise ERPs (Banner, Blackboard) cost \$10,000–\$50,000 annually, demand heavy server infrastructure, and ignore student-driven CR workflows.
* **Key Visual:** Comparison graphic: Chaos diagram of 5 disconnected apps vs. Single unified ClassMate icon.
* **Speaker Notes:**
  > "In universities across South Asia, despite high smartphone penetration, students still face administrative chaos. Announcements are lost in group chats, manual paper attendance wastes 15% of class time, and students have zero visibility into delayed campus buses. Meanwhile, commercial ERPs are completely unaffordable. This inspired our research questions."

---

### Slide 3: Research Questions & Core Thesis (Time: 2:15 – 3:15)
* **Slide Title:** Research Questions & Architectural Goals
* **Talking Points:**
  * **RQ1 (Unification):** How to integrate academic routines, notices, materials, and emergency blood networks without centralized bottlenecks?
  * **RQ2 (Low-Cost IoT):** Can sub-\$15 commodity hardware deliver reliable, anti-proxy classroom attendance under weak campus Wi-Fi?
  * **RQ3 (Zero-Cost Cloud):** How to sustain a university platform on cloud-native free tiers without risking billing lockouts?
  * **RQ4 (Transit Telemetry):** How to deliver live bus tracking without mobile blackouts or battery exhaustion?
* **Key Visual:** Table mapping RQs 1-4 directly to ClassMate's 4 architectural solutions.
* **Speaker Notes:**
  > "Our research thesis proves that by combining modern reactive mobile architectures, edge IoT microcontrollers, and cloud-native database security, universities can deploy an enterprise-grade platform at zero recurring cost."

---

### Slide 4: System Architecture & The Zero-Cost Paradigm (Time: 3:15 – 4:30)
* **Slide Title:** Tri-Tier Architecture & Zero-Cost Cloud Ecosystem
* **Talking Points:**
  * **Client Edge:** Native Android (Kotlin MVVM) + Administrative Web Console (Next.js TypeScript).
  * **Cloud Backend:** Supabase Managed PostgreSQL (60 relational schemas) + Cloudflare R2 (zero egress fees).
  * **Storage Circuit Breaker:** Automated database triggers track storage in `hosting_usage_snapshots`; locks uploads at 9.90 GB (99% capacity) to prevent billing.
  * **Zero-Cost Distribution:** Cryptographically signed APKs distributed via GitHub Releases global CDN with SHA-256 verification.
* **Key Visual:** Tri-tier architectural diagram (Android Client <-> Supabase RLS / R2 <-> ESP32 IoT Nodes).
* **Speaker Notes:**
  > "Our architecture is built around the Zero-Cost Infrastructure Paradigm. We orchestrated Supabase PostgreSQL, Cloudflare R2, GitHub Releases, and FCM v1. To protect against unexpected cloud charges, we built an automated Storage Circuit Breaker that locks uploads if storage ever approaches 9.90 GB."

---

### Slide 5: Mobile Client Engineering (Android / Kotlin) (Time: 4:30 – 5:45)
* **Slide Title:** Native Android Client: Scale, Offline Caching & UI
* **Talking Points:**
  * **Codebase Scale:** 298 Kotlin files, 78,546 lines of code, 613 XML resources.
  * **Modern Patterns:** MVVM architecture, Kotlin Coroutines, StateFlow, Room local database caching.
  * **Dynamic CR Setup:** Interim CR onboarding prompts for term selection, eliminating blank configuration states.
  * **Performance:** Cold start latency of 840 ms; warm resume in 210 ms.
* **Key Visual:** Three high-resolution screenshot plates showing Notice Feed, Course Hub, and Weekly Timetable.
* **Speaker Notes:**
  > "The mobile client is written entirely in native Kotlin. It features an offline-first architecture with local Room caching. In production release 1.1.35, we solved interim CR onboarding: new batch representatives are dynamically guided to publish running semesters without needing manual IT database intervention."

---

### Slide 6: IoT RFID Hardware Subsystem (\$14.50 BOM) (Time: 5:45 – 7:15)
* **Slide Title:** Edge Hardware: ESP32 + RC522 RFID Attendance Terminal
* **Talking Points:**
  * **Hardware Composition:** ESP32 DevKit V1 (dual-core Xtensa 240 MHz), MFRC522 RFID (SPI), SSD1306 OLED (I2C), piezo buzzer.
  * **Bill of Materials:** Complete classroom node cost is **\$14.50 USD**, compared to \$250–\$800 for commercial turnstiles.
  * **Firmware Intelligence:** Non-blocking state machine; SPIFFS flash buffer stores 256 offline scans during campus Wi-Fi drops.
  * **Anti-Passback Debounce:** 1500 ms hardware debounce prevents accidental double taps.
* **Key Visual:** Hardware wiring schematic table (MOSI, MISO, SCK, SDA) alongside a photo/render of the terminal node.
* **Speaker Notes:**
  > "For classroom attendance, we rejected expensive biometric turnstiles and engineered an open terminal powered by an ESP32 and RC522 RFID reader over SPI. The entire hardware BOM is just \$14.50 USD. If Wi-Fi fails, the terminal buffers scans in local SPIFFS flash memory and syncs them once the connection returns."

---

### Slide 7: Atomic Database Processing & Instant Push (Time: 7:15 – 8:15)
* **Slide Title:** Transactional Scan Engine & Sub-Second Mobile Notification
* **Talking Points:**
  * **Atomic Stored Procedure:** Single PL/pgSQL function (`process_rfid_scan`) handles active session check, student lookup, duplicate detection, and triage logging in one transaction.
  * **Instant Student Transparency:** PostgreSQL trigger invokes FCM v1 API; pushes attendance confirmation to the student's phone.
  * **Empirical Latency:** Card tap to phone chime takes **680 ms** total roundtrip.
  * **Unregistered Card Triage:** Unknown RFID UIDs are logged to `unregistered_rfid_scans`, allowing representatives to pair new cards in 1 click.
* **Key Visual:** Timing sequence diagram: RFID Tap (0ms) -> ESP32 HTTP POST (120ms) -> PostgreSQL Stored Proc (280ms) -> FCM Push (450ms) -> Phone Alert (680ms).
* **Speaker Notes:**
  > "When a student taps their card, a PostgreSQL stored procedure validates the session and records attendance in a single atomic transaction. Within 680 ms, the student receives a push notification confirming their attendance. This guarantees complete transparency and eliminates attendance disputes."

---

### Slide 8: Live Transit Telemetry & Zero-Blackscreen Bridge (Time: 8:15 – 9:15)
* **Slide Title:** Geospatial Tracking: Local Asset Bundling & Battery Optimization
* **Talking Points:**
  * **The Problem:** Remote mapping webviews display white/black screens in weak 3G/4G coverage.
  * **Hybrid Bridge Solution:** Leaflet.js, CSS, and Socket.io libraries are pre-bundled directly in Android APK assets.
  * **Performance Result:** Instant **0 ms map render** from local disk; zero cellular dependency for map initialization.
  * **Battery Optimization:** `ClassMateTransitManager.kt` automatically disconnects the WebSocket stream when the activity is paused, preventing idle battery drain.
* **Key Visual:** Architecture diagram showing Android WebView loading local assets and ingesting Socket.io telemetry from `transport.mbstu.ac.bd`.
* **Speaker Notes:**
  > "For bus tracking, rather than loading an external web page that blacks out on weak mobile networks, we bundled Leaflet and map stylesheets directly into the APK. The map loads in zero milliseconds, and we pause the telemetry socket whenever the user leaves the screen, ensuring zero battery drain."

---

### Slide 9: Security, Multi-Tenancy & Governance (Time: 9:15 – 10:15)
* **Slide Title:** Defense-in-Depth via PostgreSQL Row Level Security (RLS)
* **Talking Points:**
  * **Decentralized Governance:** Super-Admin, Faculty Teachers, Batch CRs, and Students.
  * **Database-Level Defense:** Authorization is enforced at the PostgreSQL RLS layer—not on the mobile client.
  * **Batch Multi-Tenancy:** Students can strictly read only their own batch's academic routines, evaluations, and notices.
  * **Cryptographic Binary Integrity:** Release APKs signed with RSA-2048 keystore; SHA-256 verification on update downloads.
* **Key Visual:** RLS policy code snippet alongside the multi-tier role hierarchy diagram.
* **Speaker Notes:**
  > "Security is enforced at the database level using PostgreSQL Row Level Security. Even if someone intercepts a token or modifies the client, the database mathematically isolates each batch's data. Furthermore, all mobile updates are verified against SHA-256 hashes before installation."

---

### Slide 10: Empirical Evaluation & Test Sign-Off (Time: 10:15 – 11:15)
* **Slide Title:** Empirical Verification: 86 Automated Tests & Benchmarks
* **Talking Points:**
  * **Android Client Tests:** 47 / 47 Unit Tests Passed (13.8s execution time; covers timetable mappers, bus filters, calendar rules).
  * **Web Dashboard Tests:** 39 / 39 Unit Tests Passed (3.82s execution time; covers natural roll sorting, batch fund calculations).
  * **Overall Reliability:** **100% pass rate across 86 automated tests**, with zero TypeScript errors.
  * **Network Efficiency:** 62 KB delta sync payload vs. 4.8 MB for traditional web portals (98.7% bandwidth reduction).
* **Key Visual:** Benchmark comparison charts and terminal test execution sign-off screenshot.
* **Speaker Notes:**
  > "We verified the entire system with 86 automated tests across Android and Web ecosystems, achieving a 100% pass rate. Our network delta sync consumes just 62 KB, representing a 98.7% reduction in bandwidth compared to conventional university portals."

---

### Slide 11: Limitations & Future Roadmap (Time: 11:15 – 11:50)
* **Slide Title:** Engineering Limitations & Future Roadmap
* **Talking Points:**
  * **Known Constraints:** Mifare Classic UID cloning vulnerability; highway cellular dead-zones; aggressive Android OEM battery managers.
  * **Future Work 1 (Cryptographic NFC):** Host Card Emulation (HCE) with rolling TOTP cryptographic tokens.
  * **Future Work 2 (Edge Vision):** Upgrading terminal with ESP32-CAM for on-device facial feature vector matching.
  * **Future Work 3 (Institutional Multi-Tenancy):** Turnkey institutional provisioning for other universities across Bangladesh.
* **Key Visual:** Scalability roadmap diagram spanning 2026–2027.
* **Speaker Notes:**
  > "We openly acknowledge current limitations: Mifare Classic cards can be cloned, and rural transit zones suffer packet jitter. Our roadmap includes cryptographic smartphone NFC emulation and vision-assisted edge verification with ESP32-CAM."

---

### Slide 12: Conclusion & Summary (Time: 11:50 – 12:00)
* **Slide Title:** Conclusion & Academic Impact
* **Talking Points:**
  * **Mission Delivered:** Proved that a high-performance, integrated smart campus platform can operate sustainably at **\$0.00 recurring cloud cost**.
  * **Real Impact at MBSTU:** Eliminated notice fragmentation, digitized classroom attendance in 680 ms, and gave students live transit visibility.
  * **Open Blueprint:** Fully documented, tested, and reproducible for universities across developing nations.
  * **Contact & Codebase:** Shuaib Ahmed | MBSTU ICT | Production Release v1.1.35
* **Key Visual:** Summary takeaway card + QR code to GitHub Release and Research Dossier PDF.
* **Speaker Notes:**
  > "In conclusion, ClassMate proves that resource-constrained universities do not need to wait for multi-thousand-dollar ERP budgets to deliver modern digital experiences to their students. Thank you, and I look forward to your questions."
