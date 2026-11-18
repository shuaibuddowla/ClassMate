import os
import re

def update_docx():
    path = "scripts/generate_showcase_docx.py"
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Update Section 2 (System Architecture)
    s2_old_pattern = r'    add_custom_heading\("2\. System Architecture & Multi-Platform Ecosystem"\).*?doc\.add_paragraph\(\)\.paragraph_format\.space_after = Pt\(6\)'
    
    s2_new = '''    add_custom_heading("2. System Architecture & Multi-Platform Ecosystem")

    p_arch_intro = doc.add_paragraph(
        "ClassMate is architected as an interconnected four-tier ecosystem, establishing a unified operational workflow across mobile, web, and IoT hardware:"
    )
    p_arch_intro.paragraph_format.space_after = Pt(4)

    arch_rows = [
        ("Architecture Tier", "Target Audience & Role", "Functional Scope & Operational Workflow"),
        ("1. Student & CR Mobile Client (Android)", "Students & Class Representatives (Daily Academic Hub)", "Dynamic date-aware timetables with live period countdowns; permanent course materials vault with offline PDF caching; targeted push notice feed with read/seen telemetry; live GPS transit bus tracking; and campus emergency blood donor directory."),
        ("2. Faculty & Admin Web Portal (Next.js / PWA)", "Course Instructors, Department Heads & Admins", "Live classroom attendance session dashboard; 1-click official Excel matrix export; syllabus & routine schedule management; multi-teacher course assignments; and instant campus-wide notice broadcast. Accessible across desktop browsers and iOS."),
        ("3. Classroom Edge-IoT Station (Embedded)", "Physical Classroom Entry Points & Labs", "Sub-50ms contactless NFC student smart card tapping; local in-memory roster lookup for instant visual OLED ID confirmation; buzzer audio feedback; and hardware anti-proxy duplicate rejection."),
        ("4. Cloud Backend & Storage Tier (Supabase + R2)", "Central Platform & Data Backbone", "Authoritative multi-tenant relational database; Row-Level Security (RLS) role isolation; atomic PL/pgSQL attendance transaction logging; distributed global CDN object storage for academic files; and FCM push notification dispatch.")
    ]

    t_arch = doc.add_table(rows=len(arch_rows), cols=3)
    t_arch.alignment = WD_TABLE_ALIGNMENT.CENTER
    t_arch.autofit = False
    col_w = [Inches(1.6), Inches(1.8), Inches(3.6)]

    for row_idx, data in enumerate(arch_rows):
        row = t_arch.rows[row_idx]
        for col_idx, text in enumerate(data):
            cell = row.cells[col_idx]
            cell.width = col_w[col_idx]
            p = cell.paragraphs[0]
            p.paragraph_format.line_spacing = 1.1
            p.paragraph_format.space_after = Pt(2)
            set_cell_margins(cell, top=80, bottom=80, left=100, right=100)

            if row_idx == 0:
                set_cell_background(cell, "1E3A8A")
                r = p.add_run(text)
                r.bold = True
                r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                r.font.size = Pt(9)
            else:
                if row_idx % 2 == 1:
                    set_cell_background(cell, "FFFFFF")
                else:
                    set_cell_background(cell, "F8FAFC")
                r = p.add_run(text)
                r.font.size = Pt(8.5)
                if col_idx == 0:
                    r.bold = True
                    r.font.color.rgb = c_primary
                else:
                    r.font.color.rgb = c_slate

    p_flow = doc.add_paragraph()
    p_flow.paragraph_format.space_before = Pt(4)
    p_flow.paragraph_format.space_after = Pt(6)
    p_flow.paragraph_format.line_spacing = 1.15
    r_flow_h = p_flow.add_run("End-to-End Operational Workflow: ")
    r_flow_h.bold = True
    r_flow_h.font.color.rgb = c_primary
    r_flow_h.font.size = Pt(8.5)
    r_flow_t = p_flow.add_run(
        "(1) Attendance Loop: When a student taps their card on the Edge-IoT Station, the device verifies the ID in RAM (<1ms) and commits it to NVS flash. It flushes the batch payload to the Cloud Backend, which executes an atomic PostgreSQL stored procedure. The Teacher Web Portal reflects the tap in real time via WebSockets, and the Student Mobile App logs verified attendance. "
        "(2) Academic Notice Loop: When an instructor posts a notice or cancels a class via Web or Mobile, Cloudflare R2 stores attachments, the database triggers Firebase Cloud Messaging (FCM), and all enrolled students receive high-priority alerts with automated routine card cancellations."
    )
    r_flow_t.font.color.rgb = c_slate
    r_flow_t.font.size = Pt(8.5)'''

    content, n1 = re.subn(s2_old_pattern, s2_new, content, flags=re.DOTALL)
    print(f"generate_showcase_docx.py Section 2 replaced: {n1}")

    # 2. Update Section 5 (Comprehensive Technology Stack)
    s5_old_pattern = r'    add_custom_heading\("5\. Comprehensive Technology Stack"\).*?doc\.add_paragraph\(\)\.paragraph_format\.space_after = Pt\(8\)'

    s5_new = '''    add_custom_heading("5. Comprehensive Technology Stack")

    tech_rows = [
        ("Engineering Domain", "Core Technologies & Toolchain", "Engineering Rationale & Architectural Purpose"),
        ("Mobile Client Architecture", "Kotlin 2.x, Android Jetpack, Material Design 3, MVVM, Coroutines, StateFlow, ViewBinding, WorkManager, OkHttp3, Retrofit2", "Native Android engineering delivering fluid 60fps UI, lifecycle-aware reactive state streams, battery-optimized background workers, and resilient offline-first caching."),
        ("Web & Desktop Engineering", "Next.js 16 (App Router), React 19, TypeScript 5, Tailwind CSS, Lucide Icons, Web Workers, Vercel Edge", "High-performance server-side rendering (SSR), end-to-end type safety across API contracts, responsive desktop layout for faculty operations, and PWA cross-platform mobile access."),
        ("Backend & Database Tier", "Supabase, PostgreSQL 15, Row-Level Security (RLS), PL/pgSQL Stored Procedures, Vercel Serverless Functions", "ACID-compliant relational integrity, fine-grained multi-tenant role enforcement at the database layer (preventing cross-batch data leakage), and atomic execution of concurrent attendance taps."),
        ("Object Storage & Global CDN", "Cloudflare R2 (S3-Compatible Object Storage), Anycast Edge CDN", "Zero-egress-fee distributed cloud storage ensuring rapid, cost-effective delivery of large academic PDF lecture notes, syllabi, books, and question papers."),
        ("Edge-IoT & Embedded Systems", "C++17, ESP-IDF / Arduino Core, FreeRTOS, NVS Flash (Preferences), MFRC522 (SPI), SSD1306 (I2C)", "Asymmetric dual-core task segregation (real-time hardware RFID sensing on Core 1 vs. network telemetry on Core 0), power-safe transactional journaling, and self-healing I2C bus recovery."),
        ("Realtime, Push & Geolocation", "Firebase Cloud Messaging (FCM), Supabase Realtime (WebSockets), Android Location Services / GPS Geolocation", "Low-latency bi-directional state synchronization, battery-efficient real-time GPS telemetry for campus transit buses, and high-priority push broadcasts for urgent academic alerts.")
    ]

    t_tech = doc.add_table(rows=len(tech_rows), cols=3)
    t_tech.alignment = WD_TABLE_ALIGNMENT.CENTER
    t_tech.autofit = False
    col_w_tech = [Inches(1.5), Inches(2.6), Inches(2.9)]

    for row_idx, data in enumerate(tech_rows):
        row = t_tech.rows[row_idx]
        for col_idx, text in enumerate(data):
            cell = row.cells[col_idx]
            cell.width = col_w_tech[col_idx]
            p = cell.paragraphs[0]
            p.paragraph_format.line_spacing = 1.1
            p.paragraph_format.space_after = Pt(2)
            set_cell_margins(cell, top=80, bottom=80, left=100, right=100)

            if row_idx == 0:
                set_cell_background(cell, "1E3A8A")
                r = p.add_run(text)
                r.bold = True
                r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                r.font.size = Pt(9)
            else:
                if row_idx % 2 == 1:
                    set_cell_background(cell, "FFFFFF")
                else:
                    set_cell_background(cell, "F8FAFC")
                r = p.add_run(text)
                r.font.size = Pt(8.5)
                if col_idx == 0:
                    r.bold = True
                    r.font.color.rgb = c_primary
                else:
                    r.font.color.rgb = c_slate

    doc.add_paragraph().paragraph_format.space_after = Pt(8)'''

    content, n2 = re.subn(s5_old_pattern, s5_new, content, flags=re.DOTALL)
    print(f"generate_showcase_docx.py Section 5 replaced: {n2}")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def update_pdf():
    path = "scripts/generate_showcase_report.py"
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Update Section 2 in PDF
    s2_old_pattern = r'    story\.append\(Paragraph\("2\. System Architecture & Multi-Platform Ecosystem", h1_style\)\).*?story\.append\(Spacer\(1, 8\)\)'

    s2_new = '''    story.append(Paragraph("2. System Architecture & Multi-Platform Ecosystem", h1_style))
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
        caption_style
    ))
    story.append(Spacer(1, 6))'''

    content, n1 = re.subn(s2_old_pattern, s2_new, content, flags=re.DOTALL)
    print(f"generate_showcase_report.py Section 2 replaced: {n1}")

    # 2. Update Section 5 in PDF
    s5_old_pattern = r'    story\.append\(Paragraph\("5\. Comprehensive Technology Stack", h1_style\)\).*?story\.append\(Spacer\(1, 10\)\)'

    s5_new = '''    story.append(Paragraph("5. Comprehensive Technology Stack", h1_style))

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
    story.append(Spacer(1, 10))'''

    content, n2 = re.subn(s5_old_pattern, s5_new, content, flags=re.DOTALL)
    print(f"generate_showcase_report.py Section 5 replaced: {n2}")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

if __name__ == "__main__":
    update_docx()
    update_pdf()
