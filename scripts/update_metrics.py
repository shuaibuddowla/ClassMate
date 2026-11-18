import re

def update_file(filepath, is_reportlab=False):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Match old metrics table
    old_pattern = r'metrics_data = \[[\s\S]*?\]' if is_reportlab else r'metrics_rows = \[[\s\S]*?\]'

    if is_reportlab:
        new_table = '''metrics_data = [
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
    ]'''
    else:
        new_table = '''metrics_rows = [
        ("System Dimension / Module", "Traditional Fragmented / Manual Workflow", "ClassMate Production Platform (Deployed at MBSTU)"),
        ("1. Academic Routine & Scheduling", "Scattered image screenshots of routine; confusion over room shifts & makeup slots; CRs overwhelmed with repetitive queries.", "Dynamic date-aware timetable with live period countdowns, automated cancellation overrides, room numbers, and teacher tags."),
        ("2. Course Materials & Question Bank", "Buried under hundreds of WhatsApp/Messenger chats; expired Drive links; 30–60 min wasted hunting for lecture slides before exams.", "Permanent course vault indexed by semester, subject code, and category (Syllabus, Lecture Notes, Lab Manuals, Question Bank)."),
        ("3. Urgent Notices & Cancellations", "Crucial announcements missed in muted group chats; students travel all the way to campus unaware a class was cancelled.", "Dedicated academic notice feed with push alerts, read/seen telemetry, and automatic daily timetable cancellation flags."),
        ("4. Classroom Attendance Speed", "Roll calls steal 10–15 minutes of 50-minute lectures; teachers spend hours retyping paper records into Excel.", "< 50 milliseconds per student tap; zero lecture disruption; automated real-time analytics & 1-click official Excel matrix export."),
        ("5. Attendance Integrity & Anti-Proxy", "High rate of proxy signatures ('signing for absent friends'); paper sheets damaged, lost, or disputed.", "Physical NFC smart card hardware verification; sub-millisecond edge rejection of duplicate scans; tamper-proof audit trail."),
        ("6. Network Resilience & Offline Sync", "Cloud apps freeze or lose data during campus Wi-Fi drops; paper sheets require physical collection.", "100% data preservation via ESP32 NVS Flash Journaling; zero data loss even during complete power or internet outages."),
        ("7. Campus Transit & Live GPS Tracking", "Students guess bus timings or wait blindly at stops; missed morning transit due to unpredictable traffic/depot delays.", "Live satellite GPS bus tracking, route corridor waypoints, departure countdowns, and fleet vehicle status (সোনারতরী, গীতাঞ্জলি)."),
        ("8. Campus Emergency Medical Response", "Unstructured, frantic Facebook/chat posts; critical delays of hours/days attempting to locate compatible blood donors.", "Centralized, verified batch blood donor directory allowing students and patients to connect with compatible donors in minutes."),
        ("9. CR & Faculty Administrative Burden", "Class Representatives and teachers spend hours manually tracking rosters, answering individual texts, and managing files.", "Unified CR & Teacher Portals with one-click routine overrides, multi-teacher course assignment, and automated batch notifications.")
    ]'''

    content = re.sub(old_pattern, new_table, content, count=1)
    
    # Update intro text to explicitly mention CSE-22 and CSE-23 across all modules
    content = content.replace(
        "at Mawlana Bhashani Science and Technology University (MBSTU):",
        "at the CSE-22 and CSE-23 batches of Mawlana Bhashani Science and Technology University (MBSTU) across all academic and operational modules:"
    )
    content = content.replace(
        "at CSE-22, CSE-23 batch of MBSTU:",
        "at the CSE-22 and CSE-23 batches of Mawlana Bhashani Science and Technology University (MBSTU) across all academic and operational modules:"
    )

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Successfully updated {filepath}")

if __name__ == '__main__':
    update_file('scripts/generate_showcase_docx.py', is_reportlab=False)
    update_file('scripts/generate_showcase_report.py', is_reportlab=True)
