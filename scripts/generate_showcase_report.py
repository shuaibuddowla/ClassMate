import os
import sys
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.lib.units import inch
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, KeepTogether, PageBreak, HRFlowable
)
from reportlab.pdfgen import canvas

class NumberedCanvas(canvas.Canvas):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._saved_page_states = []

    def showPage(self):
        self._saved_page_states.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        num_pages = len(self._saved_page_states)
        for state in self._saved_page_states:
            self.__dict__.update(state)
            self.draw_page_decorations(num_pages)
            super().showPage()
        super().save()

    def draw_page_decorations(self, page_count):
        self.saveState()
        self.setFont("Helvetica", 8)
        self.setFillColor(colors.HexColor("#64748B"))
        
        # Header (Pages 2+)
        if self._pageNumber > 1:
            self.drawString(54, 11 * inch - 36, "ClassMate — Project Showcase & Technical Engineering Report")
            self.drawRightString(8.5 * inch - 54, 11 * inch - 36, "Mawlana Bhashani Science and Technology University")
            self.setStrokeColor(colors.HexColor("#CBD5E1"))
            self.setLineWidth(0.5)
            self.line(54, 11 * inch - 42, 8.5 * inch - 54, 11 * inch - 42)

        # Footer (All pages)
        self.setStrokeColor(colors.HexColor("#E2E8F0"))
        self.setLineWidth(0.5)
        self.line(54, 45, 8.5 * inch - 54, 45)
        
        self.drawString(54, 32, "Confidential — Prepared for Project Showcasing & Competition Submission")
        page_text = f"Page {self._pageNumber} of {page_count}"
        self.drawRightString(8.5 * inch - 54, 32, page_text)
        self.restoreState()

def build_pdf(filename="ClassMate_Project_Showcase_Report.pdf"):
    doc = SimpleDocTemplate(
        filename,
        pagesize=letter,
        leftMargin=54,
        rightMargin=54,
        topMargin=54,
        bottomMargin=54
    )

    styles = getSampleStyleSheet()

    # Custom styles
    primary = colors.HexColor("#1E3A8A")   # Deep Indigo/Blue
    accent = colors.HexColor("#2563EB")    # Bright Royal Blue
    dark = colors.HexColor("#0F172A")      # Slate 900
    slate = colors.HexColor("#334155")     # Slate 700
    muted = colors.HexColor("#64748B")     # Slate 500
    bg_light = colors.HexColor("#F8FAFC")  # Slate 50
    card_border = colors.HexColor("#E2E8F0")

    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=21,
        leading=25,
        textColor=primary,
        spaceAfter=4
    )

    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=10.5,
        leading=14,
        textColor=accent,
        spaceAfter=10
    )

    meta_style = ParagraphStyle(
        'MetaStyle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8.5,
        leading=12,
        textColor=slate
    )

    h1_style = ParagraphStyle(
        'Heading1_Custom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=12.5,
        leading=16,
        textColor=primary,
        spaceBefore=12,
        spaceAfter=5,
        keepWithNext=True
    )

    body_style = ParagraphStyle(
        'Body_Custom',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8.8,
        leading=12.6,
        textColor=slate,
        spaceAfter=5
    )

    callout_style = ParagraphStyle(
        'CalloutText',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8,
        leading=11,
        textColor=slate
    )

    gallery_title_style = ParagraphStyle(
        'GalleryTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=8,
        leading=10,
        textColor=primary,
        alignment=1 # Center
    )

    gallery_desc_style = ParagraphStyle(
        'GalleryDesc',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=7,
        leading=9,
        textColor=slate,
        alignment=1 # Center
    )

    bullet_style = ParagraphStyle(
        'Bullet_Custom',
        parent=body_style,
        leftIndent=12,
        firstLineIndent=-8,
        spaceAfter=3
    )

    table_header_style = ParagraphStyle(
        'TableHeader',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=8.5,
        leading=11,
        textColor=colors.white
    )

    table_cell_style = ParagraphStyle(
        'TableCell',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8,
        leading=11,
        textColor=dark
    )

    story = []

    # ----------------------------------------------------
    # HEADER / TITLE BLOCK WITH OFFICIAL ANDROID LOGO
    # ----------------------------------------------------
    android_logo_path = 'app/src/main/res/drawable-xxxhdpi/ic_classmate_logo.png'
    if not os.path.exists(android_logo_path):
        android_logo_path = 'app/src/main/res/drawable-xxhdpi/ic_classmate_logo.png'

    logo_flowable = None
    if os.path.exists(android_logo_path):
        logo_flowable = Image(android_logo_path, width=1.15*inch, height=1.15*inch)

    header_table_data = [
        [
            logo_flowable if logo_flowable else Paragraph("<b>ClassMate</b>", title_style),
            [
                Paragraph("<b>ClassMate: A Connected Classroom Academic Platform & IoT Attendance System</b>", title_style),
                Paragraph("An Integrated Smart Campus Management Ecosystem — Mobile (Android), Web Platform, Cloud Architecture & Edge-IoT Hardware", subtitle_style),
                Paragraph("<b>Developer & Author:</b> Mohammad Shuaib-Ud-Dowla &nbsp;|&nbsp; <b>Institution:</b> Mawlana Bhashani Science and Technology University (MBSTU)<br/><b>Department:</b> Computer Science and Engineering (CSE) &nbsp;|&nbsp; <b>Live System:</b> https://classmatebd.vercel.app", meta_style)
            ]
        ]
    ]

    header_table = Table(header_table_data, colWidths=[1.35*inch, 5.65*inch])
    header_table.setStyle(TableStyle([
        ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
        ('BOTTOMPADDING', (0,0), (-1,-1), 0),
        ('TOPPADDING', (0,0), (-1,-1), 0),
        ('LEFTPADDING', (0,0), (-1,-1), 0),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(header_table)
    story.append(Spacer(1, 8))
    story.append(HRFlowable(width="100%", thickness=1.5, color=accent, spaceBefore=4, spaceAfter=10))

    # ----------------------------------------------------
    # EXECUTIVE SUMMARY CALLOUT BOX
    # ----------------------------------------------------
    exec_summary_text = (
        "<b>Executive Summary:</b> ClassMate is a production-deployed, multi-platform smart campus ecosystem engineered to "
        "eliminate the chaotic fragmentation of university academic life. It replaces disjointed messaging groups, "
        "buried lecture files, outdated paper roll calls, and isolated campus transit schedules with an integrated, "
        "single-pane-of-glass architecture. Spanning an offline-first native Android app, a modern Next.js 16 web portal, "
        "a scalable multi-tenant Supabase/PostgreSQL backend, and custom physical ESP32 Edge-IoT attendance stations with "
        "zero-loss flash journaling, ClassMate serves real university batches at MBSTU with sub-millisecond responsiveness."
    )
    
    summary_box = Table([[Paragraph(exec_summary_text, callout_style)]], colWidths=[7.0*inch])
    summary_box.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,-1), colors.HexColor("#EFF6FF")),
        ('BOX', (0,0), (-1,-1), 1, colors.HexColor("#BFDBFE")),
        ('LEFTPADDING', (0,0), (-1,-1), 10),
        ('RIGHTPADDING', (0,0), (-1,-1), 10),
        ('TOPPADDING', (0,0), (-1,-1), 7),
        ('BOTTOMPADDING', (0,0), (-1,-1), 7),
    ]))
    story.append(summary_box)
    story.append(Spacer(1, 8))

    # ----------------------------------------------------
    # SECTION 1: THE REAL PROBLEMS SOLVED
    # ----------------------------------------------------
    story.append(Paragraph("1. The Core Problems ClassMate Solves", h1_style))
    story.append(Paragraph(
        "University students, Class Representatives (CRs), and teachers face constant friction caused by informal communication tools "
        "never designed for structured academic governance:", body_style
    ))

    problems = [
        (
            "The 'Chat Group Chaos' & Scattered Academic Resources:",
            "Universities conventionally communicate via WhatsApp, Messenger, or Telegram groups. "
            "However, <i>chat apps are designed for casual messaging, not classroom management</i>. "
            "Lecture slides, lab manuals, PDFs, Google Drive links, and syllabus copies get buried under hundreds of daily text messages. "
            "Prior to exams, students waste hours frantically asking <i>'Who has the Lecture 4 slides?'</i> or <i>'Where is the question bank?'</i>. "
            "<b>ClassMate's Solution:</b> A permanent, course-structured <b>Academic Library & Question Bank</b>. Materials are categorized strictly by "
            "semester, course code, and type (Notes, Lab, Questions), remaining indexed, permanent, and accessible in 1 click."
        ),
        (
            "Notice Chaos, Lost Announcements & Teacher Disconnect:",
            "Sudden class cancellations, room changes, and assignment deadlines posted in chat groups are frequently missed because students mute noisy threads. "
            "Students often travel all the way to campus only to learn a class was cancelled hours earlier. Moreover, professors hesitate to join informal "
            "student chat groups due to privacy and noise boundaries. "
            "<b>ClassMate's Solution:</b> A dedicated, noise-free <b>Academic Notice Board with Targeted Push Notifications</b>. Announcements dynamically "
            "override and update the student's live timetable for that specific day (e.g., visually flagging 'CANCELLED' on the period card). "
            "Teachers receive a clean, professional web portal to post notices without joining student social media groups."
        ),
        (
            "The Paper Roll-Call Bottleneck & Rampant Proxy Attendance:",
            "Paper roll sheets passed down classroom aisles steal 10–15 minutes of 50-minute lectures and invite widespread proxy signatures. "
            "Existing smartphone QR-code or biometric apps crash or stall when campus Wi-Fi drops. "
            "<b>ClassMate's Solution:</b> An autonomous <b>ESP32 Edge-IoT RFID Attendance Station</b> deployed directly in classrooms. Students tap their physical NFC student ID card. "
            "The device performs an instant edge lookup (<0.001ms in RAM), verifies student identity with audio/OLED confirmation, and stores records in non-volatile flash (NVS). "
            "Punches sync to cloud databases via high-throughput batches with <b>zero data loss</b> even through complete network blackouts."
        ),
        (
            "Fragmented Campus Transit & Student Emergency Support:",
            "Students rushing to morning classes lack real-time visibility into university bus locations, causing missed transit and late attendance. "
            "Separately, emergency patient blood requests rely on uncoordinated, frantic Facebook posts. "
            "<b>ClassMate's Solution:</b> Integrated <b>Live GPS Campus Transit Tracking</b> with corridor waypoints and schedules, paired with an emergency "
            "<b>Campus Blood Donor Directory</b> connecting verified student donors directly to patients in need."
        )
    ]

    for title, desc in problems:
        story.append(Paragraph(f"• <b>{title}</b> {desc}", bullet_style))
        story.append(Spacer(1, 2))

    story.append(Spacer(1, 6))

    # ----------------------------------------------------
    # SECTION 2: ARCHITECTURE & MULTI-PLATFORM ECOSYSTEM
    # ----------------------------------------------------
    story.append(Paragraph("2. System Architecture & Multi-Platform Ecosystem", h1_style))
    story.append(Paragraph(
        "ClassMate is architected as an interconnected four-tier ecosystem, establishing a unified operational workflow across mobile, web, and IoT hardware:", body_style
    ))

    arch_data = [
        [
            Paragraph("<b>Architecture Tier</b>", table_header_style),
            Paragraph("<b>Target Audience & Role</b>", table_header_style),
            Paragraph("<b>Functional Scope & Operational Workflow</b>", table_header_style)
        ],
        [
            Paragraph("<b>1. Student & CR Mobile Client (Android)</b>", table_cell_style),
            Paragraph("Students & Class Representatives (Daily Academic Hub)", table_cell_style),
            Paragraph("Dynamic date-aware timetables with live period countdowns; permanent course materials vault with offline PDF caching; targeted push notice feed with read/seen telemetry; live GPS transit bus tracking; and campus emergency blood donor directory.", table_cell_style)
        ],
        [
            Paragraph("<b>2. Faculty & Admin Web Portal (Next.js / PWA)</b>", table_cell_style),
            Paragraph("Course Instructors, Department Heads & Admins", table_cell_style),
            Paragraph("Live classroom attendance session dashboard; 1-click official Excel matrix export; syllabus & routine schedule management; multi-teacher course assignments; and instant campus-wide notice broadcast. Accessible across desktop browsers and iOS.", table_cell_style)
        ],
        [
            Paragraph("<b>3. Classroom Edge-IoT Station (Embedded)</b>", table_cell_style),
            Paragraph("Physical Classroom Entry Points & Labs", table_cell_style),
            Paragraph("Sub-50ms contactless NFC student smart card tapping; local in-memory roster lookup for instant visual OLED ID confirmation; buzzer audio feedback; and hardware anti-proxy duplicate rejection.", table_cell_style)
        ],
        [
            Paragraph("<b>4. Cloud Backend & Storage Tier (Supabase + R2)</b>", table_cell_style),
            Paragraph("Central Platform & Data Backbone", table_cell_style),
            Paragraph("Authoritative multi-tenant relational database; Row-Level Security (RLS) role isolation; atomic PL/pgSQL attendance transaction logging; distributed global CDN object storage for academic files; and FCM push notification dispatch.", table_cell_style)
        ]
    ]

    arch_table = Table(arch_data, colWidths=[1.8*inch, 1.8*inch, 3.4*inch])
    arch_table.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.5, card_border),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, bg_light]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(arch_table)
    story.append(Spacer(1, 4))
    story.append(Paragraph(
        "<b>End-to-End Operational Workflow:</b> <i>(1) Attendance Loop:</i> When a student taps their card on the Edge-IoT Station, the device verifies the ID in RAM (&lt;1ms) and commits it to NVS flash. It flushes the batch payload to Cloud Backend, executing an atomic PostgreSQL stored procedure. Teacher Web Portal reflects the tap in real time via WebSockets, and Student Mobile App logs verified attendance. <i>(2) Academic Notice Loop:</i> When an instructor posts a notice or cancels a class, Cloudflare R2 stores attachments, the database triggers FCM, and all enrolled students receive high-priority alerts with automated routine card cancellations.",
        callout_style
    ))
    story.append(Spacer(1, 6))

    # ----------------------------------------------------
    # SECTION 3: EMBEDDED EDGE-IOT ATTENDANCE HARDWARE
    # ----------------------------------------------------
    story.append(Paragraph("3. Deep-Dive: Edge-IoT Attendance Hardware Engineering", h1_style))
    story.append(Paragraph(
        "Unlike commercial RFID readers that require persistent cloud connectivity and hang during network latency, "
        "ClassMate features a custom <b>dual-core embedded architecture</b> engineered for extreme reliability:", body_style
    ))

    hw_features = [
        "<b>Asymmetric Dual-Core Scheduling (FreeRTOS):</b> Core 1 is dedicated exclusively to instant RFID sensing and visual/audio OLED animation (<50ms total response). Core 0 runs background HTTP network telemetry and multi-SSID cycling, guaranteeing the card reader never freezes during slow Wi-Fi handshakes.",
        "<b>Sub-Millisecond Local RAM Roster:</b> Active course sessions preload enrolled student IDs and card UIDs into an in-memory edge cache. Tapping a card executes a <b><0.001ms RAM search</b>, displaying the student's actual ID (e.g. <i>CE21001</i>) and a green checkmark instantly.",
        "<b>Fail-Safe 'Never-Drop' NVS Flash Journaling:</b> Every card tap is written to ESP32 physical Non-Volatile Storage (Flash via <code>Preferences.h</code>). Scans remain permanently preserved across sudden power unplugs or router reboots. They are erased <i>only after the cloud server returns HTTP 200 BATCH_OK</i>.",
        "<b>High-Throughput HTTPS Batch Uploading:</b> Instead of performing individual 2-second TLS handshakes for each student, the background worker collects pending scans and flushes them in a single batch request (<code>POST /api/scan</code> with <code>batch: [UID1, UID2...]</code>). This delivers a <b>25x throughput speedup</b> during rapid classroom queues.",
        "<b>I2C Bus Recovery & Standby Power:</b> The firmware features an active I2C bus recovery routine to self-heal loose jumper connections and displays a live sync badge (<code>TAP CARD | SYNC: X</code>) so instructors visually confirm when all records have synced."
    ]

    for feat in hw_features:
        story.append(Paragraph(f"• {feat}", bullet_style))
        story.append(Spacer(1, 2))

    story.append(Spacer(1, 8))

    # Page Break to dedicate Page 3 as the Grand Visual Showcase Gallery!
    story.append(PageBreak())

    # ----------------------------------------------------
    # SECTION 4: VISUAL SHOWCASE GALLERY (7 PRODUCTION SCREENSHOTS)
    # ----------------------------------------------------
    story.append(Paragraph("4. Production User Interface & Feature Gallery", h1_style))
    story.append(Paragraph(
        "Production screenshots from the live ClassMate Android ecosystem demonstrating daily academic and campus transit workflows:", body_style
    ))
    story.append(Spacer(1, 4))

    # Asset paths
    img_t = 'showcase_assets/screen_live_timetable.png'
    img_n = 'showcase_assets/screen_notice_feed.png'
    img_c = 'showcase_assets/screen_courses_hub.png'
    img_cf = 'showcase_assets/screen_course_details.png'
    img_gps = 'showcase_assets/screen_bus_map_gps.png'
    img_b = 'showcase_assets/screen_bus_schedule.png'
    img_cal = 'showcase_assets/screen_academic_calendar.png'

    # Asset paths (5 final screenshots)
    img_t = 'showcase_assets/screen_live_timetable_v2.png'
    img_n = 'showcase_assets/screen_notice_feed_v2.png'
    img_c = 'showcase_assets/screen_courses_hub_v2.png'
    img_gps = 'showcase_assets/screen_bus_map_gps_v2.png'
    img_fund = 'showcase_assets/screen_batch_fund_v2.png'

    # Row 1: Academic & Notice Workflows (3 screenshots)
    w_row1 = 1.60 * inch
    h_row1 = 2.95 * inch

    row1_imgs = [
        Image(img_t, width=w_row1, height=h_row1),
        Image(img_n, width=w_row1, height=h_row1),
        Image(img_c, width=w_row1, height=h_row1),
    ]
    row1_texts = [
        [
            Paragraph("<b>Live Dynamic Timetable</b>", gallery_title_style),
            Paragraph("Date-aware routine with period countdowns & lab indicators", gallery_desc_style)
        ],
        [
            Paragraph("<b>Official Notices & Telemetry</b>", gallery_title_style),
            Paragraph("Urgent push notices, file attachments & seen-counter", gallery_desc_style)
        ],
        [
            Paragraph("<b>Courses & Resource Hub</b>", gallery_title_style),
            Paragraph("Semester courses, lecture vault & attendance logs", gallery_desc_style)
        ]
    ]

    grid_row1 = Table([row1_imgs, row1_texts], colWidths=[2.33*inch, 2.33*inch, 2.33*inch])
    grid_row1.setStyle(TableStyle([
        ('ALIGN', (0,0), (-1,-1), 'CENTER'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('TOPPADDING', (0,0), (-1,-1), 1),
        ('BOTTOMPADDING', (0,0), (-1,-1), 2),
        ('LEFTPADDING', (0,0), (-1,-1), 2),
        ('RIGHTPADDING', (0,0), (-1,-1), 2),
    ]))
    story.append(grid_row1)
    story.append(Spacer(1, 8))

    # Row 2: Transit Telematics & Financial Transparency (2 screenshots)
    w_row2 = 1.60 * inch
    h_row2 = 2.95 * inch

    row2_imgs = [
        Image(img_gps, width=w_row2, height=h_row2),
        Image(img_fund, width=w_row2, height=h_row2),
    ]
    row2_texts = [
        [
            Paragraph("<b>Live Satellite Transit GPS</b>", gallery_title_style),
            Paragraph("Real-time university bus tracking & campus corridor stops", gallery_desc_style)
        ],
        [
            Paragraph("<b>Batch Fund & Transparency</b>", gallery_title_style),
            Paragraph("Class treasury ledger, collection stats & payment logs", gallery_desc_style)
        ]
    ]

    grid_row2 = Table([row2_imgs, row2_texts], colWidths=[3.5*inch, 3.5*inch])
    grid_row2.setStyle(TableStyle([
        ('ALIGN', (0,0), (-1,-1), 'CENTER'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('TOPPADDING', (0,0), (-1,-1), 1),
        ('BOTTOMPADDING', (0,0), (-1,-1), 2),
        ('LEFTPADDING', (0,0), (-1,-1), 4),
        ('RIGHTPADDING', (0,0), (-1,-1), 4),
    ]))
    story.append(grid_row2)

    # Page Break to Page 4: Tech Stack, Metrics, Conclusion & Author Info
    story.append(PageBreak())

    # ----------------------------------------------------
    # SECTION 5: COMPLETE TECHNICAL INVENTORY
    # ----------------------------------------------------
    story.append(Paragraph("5. Comprehensive Technology Stack", h1_style))

    tech_data = [
        [
            Paragraph("<b>Engineering Domain</b>", table_header_style),
            Paragraph("<b>Core Technologies & Toolchain</b>", table_header_style),
            Paragraph("<b>Engineering Rationale & Architectural Purpose</b>", table_header_style)
        ],
        [
            Paragraph("<b>Mobile Client Architecture</b>", table_cell_style),
            Paragraph("Kotlin 2.x, Android Jetpack, Material Design 3, MVVM, Coroutines, StateFlow, ViewBinding, WorkManager, OkHttp3, Retrofit2", table_cell_style),
            Paragraph("Native Android engineering delivering fluid 60fps UI, lifecycle-aware reactive state streams, battery-optimized background workers, and resilient offline-first caching.", table_cell_style)
        ],
        [
            Paragraph("<b>Web & Desktop Engineering</b>", table_cell_style),
            Paragraph("Next.js 16 (App Router), React 19, TypeScript 5, Tailwind CSS, Lucide Icons, Web Workers, Vercel Edge", table_cell_style),
            Paragraph("High-performance server-side rendering (SSR), end-to-end type safety across API contracts, responsive desktop layout for faculty operations, and PWA cross-platform mobile access.", table_cell_style)
        ],
        [
            Paragraph("<b>Backend & Database Tier</b>", table_cell_style),
            Paragraph("Supabase, PostgreSQL 15, Row-Level Security (RLS), PL/pgSQL Stored Procedures, Vercel Serverless Functions", table_cell_style),
            Paragraph("ACID-compliant relational integrity, fine-grained multi-tenant role enforcement at the database layer (preventing cross-batch data leakage), and atomic execution of concurrent attendance taps.", table_cell_style)
        ],
        [
            Paragraph("<b>Object Storage & Global CDN</b>", table_cell_style),
            Paragraph("Cloudflare R2 (S3-Compatible Object Storage), Anycast Edge CDN", table_cell_style),
            Paragraph("Zero-egress-fee distributed cloud storage ensuring rapid, cost-effective delivery of large academic PDF lecture notes, syllabi, books, and question papers.", table_cell_style)
        ],
        [
            Paragraph("<b>Edge-IoT & Embedded Systems</b>", table_cell_style),
            Paragraph("C++17, ESP-IDF / Arduino Core, FreeRTOS, NVS Flash (Preferences), MFRC522 (SPI), SSD1306 (I2C)", table_cell_style),
            Paragraph("Asymmetric dual-core task segregation (real-time hardware RFID sensing on Core 1 vs. network telemetry on Core 0), power-safe transactional journaling, and self-healing I2C bus recovery.", table_cell_style)
        ],
        [
            Paragraph("<b>Realtime, Push & Geolocation</b>", table_cell_style),
            Paragraph("Firebase Cloud Messaging (FCM), Supabase Realtime (WebSockets), Android Location Services / GPS Geolocation", table_cell_style),
            Paragraph("Low-latency bi-directional state synchronization, battery-efficient real-time GPS telemetry for campus transit buses, and high-priority push broadcasts for urgent academic alerts.", table_cell_style)
        ]
    ]

    tech_table = Table(tech_data, colWidths=[1.6*inch, 2.5*inch, 2.9*inch])
    tech_table.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
        ('GRID', (0,0), (-1,-1), 0.5, card_border),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, bg_light]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(tech_table)
    story.append(Spacer(1, 10))

    # ----------------------------------------------------
    # SECTION 6: REAL-WORLD IMPACT & VERIFIED METRICS
    # ----------------------------------------------------
    story.append(Paragraph("6. Real-World Impact & Production Metrics", h1_style))
    story.append(Paragraph(
        "ClassMate is not a theoretical prototype; it is actively deployed and tested at the CSE-22 and CSE-23 batches of Mawlana Bhashani Science and Technology University (MBSTU) across all academic and operational modules:", body_style
    ))

    metrics_data = [
        [
            Paragraph("<b>System Dimension / Module</b>", table_header_style),
            Paragraph("<b>Traditional Fragmented / Manual Workflow</b>", table_header_style),
            Paragraph("<b>ClassMate Production Platform (Deployed at MBSTU)</b>", table_header_style)
        ],
        [
            Paragraph("<b>1. Academic Routine & Scheduling</b>", table_cell_style),
            Paragraph("Scattered image screenshots of routine; confusion over room shifts & makeup slots; CRs overwhelmed with repetitive queries.", table_cell_style),
            Paragraph("<b>Dynamic date-aware timetable</b> with live period countdowns, automated cancellation overrides, room numbers, and teacher tags.", table_cell_style)
        ],
        [
            Paragraph("<b>2. Course Materials & Question Bank</b>", table_cell_style),
            Paragraph("Buried under hundreds of WhatsApp/Messenger chats; expired Drive links; 30–60 min wasted hunting for lecture slides before exams.", table_cell_style),
            Paragraph("<b>Permanent course vault</b> indexed by semester, subject code, and category (Syllabus, Lecture Notes, Lab Manuals, Question Bank).", table_cell_style)
        ],
        [
            Paragraph("<b>3. Urgent Notices & Cancellations</b>", table_cell_style),
            Paragraph("Crucial announcements missed in muted group chats; students travel all the way to campus unaware a class was cancelled.", table_cell_style),
            Paragraph("<b>Dedicated academic notice feed</b> with push alerts, read/seen telemetry, and automatic daily timetable cancellation flags.", table_cell_style)
        ],
        [
            Paragraph("<b>4. Classroom Attendance Speed</b>", table_cell_style),
            Paragraph("Roll calls steal 10–15 minutes of 50-minute lectures; teachers spend hours retyping paper records into Excel.", table_cell_style),
            Paragraph("<b>< 50 milliseconds</b> per student tap; zero lecture disruption; automated real-time analytics & 1-click official Excel matrix export.", table_cell_style)
        ],
        [
            Paragraph("<b>5. Attendance Integrity & Anti-Proxy</b>", table_cell_style),
            Paragraph("High rate of proxy signatures ('signing for absent friends'); paper sheets damaged, lost, or disputed.", table_cell_style),
            Paragraph("<b>Physical NFC smart card</b> hardware verification; sub-millisecond edge rejection of duplicate scans; tamper-proof audit trail.", table_cell_style)
        ],
        [
            Paragraph("<b>6. Network Resilience & Offline Sync</b>", table_cell_style),
            Paragraph("Cloud apps freeze or lose data during campus Wi-Fi drops; paper sheets require physical collection.", table_cell_style),
            Paragraph("<b>100% data preservation</b> via ESP32 NVS Flash Journaling; zero data loss even during complete power or internet outages.", table_cell_style)
        ],
        [
            Paragraph("<b>7. Campus Transit & Live GPS Tracking</b>", table_cell_style),
            Paragraph("Students guess bus timings or wait blindly at stops; missed morning transit due to unpredictable traffic/depot delays.", table_cell_style),
            Paragraph("<b>Live satellite GPS bus tracking</b>, route corridor waypoints, departure countdowns, and fleet vehicle status (সোনারতরী, গীতাঞ্জলি).", table_cell_style)
        ],
        [
            Paragraph("<b>8. Campus Emergency Medical Response</b>", table_cell_style),
            Paragraph("Unstructured, frantic Facebook/chat posts; critical delays of hours/days attempting to locate compatible blood donors.", table_cell_style),
            Paragraph("<b>Centralized, verified batch blood donor directory</b> allowing students and patients to connect with compatible donors in minutes.", table_cell_style)
        ],
        [
            Paragraph("<b>9. CR & Faculty Administrative Burden</b>", table_cell_style),
            Paragraph("Class Representatives and teachers spend hours manually tracking rosters, answering individual texts, and managing files.", table_cell_style),
            Paragraph("<b>Unified CR & Teacher Portals</b> with one-click routine overrides, multi-teacher course assignment, and automated batch notifications.", table_cell_style)
        ]
    ]

    metrics_table = Table(metrics_data, colWidths=[2.0*inch, 2.5*inch, 2.5*inch])
    metrics_table.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
        ('GRID', (0,0), (-1,-1), 0.5, card_border),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, bg_light]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(metrics_table)
    story.append(Spacer(1, 10))

    # ----------------------------------------------------
    # CONCLUSION & SUBMISSION SIGN-OFF
    # ----------------------------------------------------
    conclusion_text = (
        "<b>Conclusion:</b> ClassMate proves that combining modern mobile engineering, reactive cloud architectures, "
        "and affordable edge-IoT hardware can radically upgrade the university experience. By addressing the deep-seated "
        "flaws of social-media-based classroom management, ClassMate delivers a scalable, highly reliable, and student-centered "
        "operating ecosystem ready for nationwide higher education adoption."
    )
    story.append(Paragraph(conclusion_text, body_style))
    story.append(Spacer(1, 8))

    signoff_table = Table([
        [
            Paragraph("<b>Project Lead & Developer:</b> Mohammad Shuaib-Ud-Dowla<br/>Department of CSE, Mawlana Bhashani Science and Technology University<br/>Email: shuaib.mbstu@gmail.com &nbsp;|&nbsp; Web: https://classmatebd.vercel.app", meta_style),
            Paragraph("<b>Project Status:</b> Active Production Deployment<br/><b>Target:</b> National & University Project Showcasing 2026<br/><b>Verified:</b> Zero-loss Hardware + Android + Web", meta_style)
        ]
    ], colWidths=[4.2*inch, 2.8*inch])
    signoff_table.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,-1), bg_light),
        ('BOX', (0,0), (-1,-1), 1, card_border),
        ('LEFTPADDING', (0,0), (-1,-1), 8),
        ('RIGHTPADDING', (0,0), (-1,-1), 8),
        ('TOPPADDING', (0,0), (-1,-1), 6),
        ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(signoff_table)

    # Build document with NumberedCanvas
    doc.build(story, canvasmaker=NumberedCanvas)
    print(f"Successfully generated {filename}")

if __name__ == "__main__":
    build_pdf()
