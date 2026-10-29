#!/usr/bin/env python3
"""
Generate a professional, publication-quality A4 PDF report listing all students who
have not paid for the ClassMate Attendance Card (RFID Hardware Attendance), excluding the admin.
"""

import os
import sys
import shutil
from datetime import datetime
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.units import inch
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether
)
from reportlab.pdfgen import canvas

# Import database access
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'capacity'))
from platform_access import query, PRODUCTION


class NumberedCanvas(canvas.Canvas):
    """
    Two-pass canvas to dynamically compute and print total page count ('Page X of Y').
    """
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
        self.setFont("Helvetica-Bold", 8)
        self.setFillColor(colors.HexColor("#475569"))

        # Running Header
        header_text = "CLASSMATE ATTENDANCE SYSTEM  |  ATTENDANCE CARD PAYMENT STATUS REPORT"
        self.drawString(36, A4[1] - 26, header_text)
        self.setFont("Helvetica", 8)
        self.setFillColor(colors.HexColor("#64748B"))
        self.drawRightString(A4[0] - 36, A4[1] - 26, "CSE 22nd Batch - MBSTU")
        self.setStrokeColor(colors.HexColor("#CBD5E1"))
        self.setLineWidth(0.6)
        self.line(36, A4[1] - 30, A4[0] - 36, A4[1] - 30)

        # Running Footer
        footer_left = "Department of Computer Science and Engineering - Mawlana Bhashani Science and Technology University"
        footer_right = f"Page {self._pageNumber} of {page_count}"
        self.setFont("Helvetica", 7.5)
        self.setFillColor(colors.HexColor("#64748B"))
        self.drawString(36, 22, footer_left)
        self.setFont("Helvetica-Bold", 8)
        self.drawRightString(A4[0] - 36, 22, footer_right)
        self.line(36, 32, A4[0] - 36, 32)

        self.restoreState()


def clean_text(text):
    if text is None:
        return ""
    return str(text).encode('ascii', 'replace').decode('ascii')


def fetch_attendance_card_data():
    batch_22_id = "de0b3379-54a0-4275-a526-5970295c787e"

    profiles = query(PRODUCTION, f"""
        select id, email, full_name, role, is_cr, student_id, mobile_number, blood_group, current_residence, home_town, rfid_card_uid
        from classmate.profiles
        where batch_id = '{batch_22_id}' or effective_batch_id = '{batch_22_id}'
        order by student_id;
    """)

    txs = query(PRODUCTION, f"""
        select id, student_name, student_id, student_profile_id, amount, title, transacted_at, created_at
        from classmate.batch_fund_transactions
        where batch_id = '{batch_22_id}' and lower(title) like '%attend%'
        order by transacted_at, created_at;
    """)

    paid_map = {}
    for t in txs:
        p_id = t.get('student_profile_id')
        if p_id:
            paid_map[p_id] = paid_map.get(p_id, 0.0) + float(t.get('amount') or 0.0)

    admin_profiles = []
    paid_students = []
    unpaid_students = []

    for p in profiles:
        # Check if admin
        if p.get('role') == 'admin' or p.get('student_id') == 'CE25045' or p.get('email') == 'ce25045@mbstu.ac.bd':
            admin_profiles.append(p)
            continue

        p_id = p['id']
        if p_id in paid_map:
            p_copy = dict(p)
            p_copy['paid_amount'] = paid_map[p_id]
            paid_students.append(p_copy)
        else:
            unpaid_students.append(p)

    return admin_profiles, paid_students, unpaid_students, txs


def build_pdf(output_path: str):
    admin_profiles, paid_students, unpaid_students, all_txs = fetch_attendance_card_data()

    doc = SimpleDocTemplate(
        output_path,
        pagesize=A4,
        leftMargin=36,
        rightMargin=36,
        topMargin=38,
        bottomMargin=38
    )

    base_styles = getSampleStyleSheet()

    # Brand Colors
    c_navy = colors.HexColor("#0F172A")       # Primary headings
    c_blue = colors.HexColor("#1E3A8A")       # Primary brand blue
    c_accent = colors.HexColor("#2563EB")     # Accent blue
    c_red = colors.HexColor("#B91C1C")        # Unpaid alert red
    c_red_bg = colors.HexColor("#FEF2F2")     # Red card background
    c_green = colors.HexColor("#047857")      # Paid green
    c_green_bg = colors.HexColor("#ECFDF5")   # Green card background
    c_slate = colors.HexColor("#475569")      # Subtitles
    c_light_bg = colors.HexColor("#F8FAFC")   # Light gray background
    c_border = colors.HexColor("#CBD5E1")     # Light border

    # Styles
    title_style = ParagraphStyle(
        'DocTitle',
        parent=base_styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=16,
        leading=19,
        textColor=c_navy
    )

    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=base_styles['Normal'],
        fontName='Helvetica',
        fontSize=8.5,
        leading=12,
        textColor=c_slate
    )

    h2_style = ParagraphStyle(
        'Heading2',
        parent=base_styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=11,
        leading=14,
        textColor=c_blue,
        spaceBefore=6,
        spaceAfter=4
    )

    body_style = ParagraphStyle(
        'Body',
        parent=base_styles['Normal'],
        fontName='Helvetica',
        fontSize=8,
        leading=10,
        textColor=c_navy
    )

    body_bold = ParagraphStyle(
        'BodyBold',
        parent=body_style,
        fontName='Helvetica-Bold'
    )

    body_center = ParagraphStyle(
        'BodyCenter',
        parent=body_style,
        alignment=1
    )

    body_center_bold = ParagraphStyle(
        'BodyCenterBold',
        parent=body_bold,
        alignment=1
    )

    th_style = ParagraphStyle(
        'TH',
        parent=base_styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=8,
        leading=10,
        textColor=colors.white,
        alignment=1
    )

    badge_unpaid = ParagraphStyle(
        'BadgeUnpaid',
        parent=base_styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=7,
        leading=9,
        textColor=c_red,
        alignment=1
    )

    badge_paid = ParagraphStyle(
        'BadgePaid',
        parent=base_styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=7,
        leading=9,
        textColor=c_green,
        alignment=1
    )

    note_style = ParagraphStyle(
        'Note',
        parent=base_styles['Normal'],
        fontName='Helvetica',
        fontSize=7.5,
        leading=10.5,
        textColor=colors.HexColor("#92400E")
    )

    story = []

    # Title & Header
    story.append(Paragraph("Attendance Card Payment Due List", title_style))
    story.append(Paragraph("Department of Computer Science and Engineering &bull; Mawlana Bhashani Science and Technology University", subtitle_style))
    gen_time_str = datetime.now().strftime("%B %d, %Y - %I:%M %p")
    story.append(Paragraph(f"Batch: <b>CSE 22 (Session: 2021-22)</b> &nbsp;|&nbsp; Target: <b>RFID Attendance Punch Card</b> &nbsp;|&nbsp; Audit Date: <b>{gen_time_str}</b>", subtitle_style))
    story.append(Spacer(1, 6))

    # Metric Cards Table (Width: A4 595 - 72 = 523 pt)
    card_fee = 40.0
    total_unpaid_amount = len(unpaid_students) * card_fee
    total_paid_collected = sum(p['paid_amount'] for p in paid_students)

    metric_data = [
        [
            Paragraph("<font size='7' color='#64748B'>TOTAL STUDENTS</font><br/><font size='12'><b>58</b></font><br/><font size='6.5' color='#64748B'>57 Students + 1 Admin</font>", body_center),
            Paragraph(f"<font size='7' color='#047857'>PAID (COLLECTED)</font><br/><font size='12' color='#047857'><b>{len(paid_students)}</b></font><br/><font size='6.5' color='#047857'>BDT {total_paid_collected:,.0f} Received</font>", body_center),
            Paragraph(f"<font size='7' color='#B91C1C'>UNPAID (DUE)</font><br/><font size='12' color='#B91C1C'><b>{len(unpaid_students)}</b></font><br/><font size='6.5' color='#B91C1C'>BDT {total_unpaid_amount:,.0f} Due</font>", body_center),
            Paragraph("<font size='7' color='#1E3A8A'>CARD UNIT FEE</font><br/><font size='12' color='#1E3A8A'><b>40 BDT</b></font><br/><font size='6.5' color='#64748B'>Per Student</font>", body_center),
            Paragraph("<font size='7' color='#475569'>ADMIN EXCLUSION</font><br/><font size='12' color='#0F172A'><b>CE25045</b></font><br/><font size='6.5' color='#475569'>M. Shuaib-Ud-Dowla</font>", body_center),
        ]
    ]

    metric_table = Table(metric_data, colWidths=[104, 105, 105, 104, 105])
    metric_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, 0), c_light_bg),
        ('BACKGROUND', (1, 0), (1, 0), c_green_bg),
        ('BACKGROUND', (2, 0), (2, 0), c_red_bg),
        ('BACKGROUND', (3, 0), (3, 0), c_light_bg),
        ('BACKGROUND', (4, 0), (4, 0), c_light_bg),
        ('BOX', (0, 0), (-1, -1), 0.75, c_border),
        ('INNERGRID', (0, 0), (-1, -1), 0.5, c_border),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
    ]))
    story.append(metric_table)
    story.append(Spacer(1, 5))

    # Note Banner
    notice_text = (
        "<b>Notice:</b> The following <b>32 students</b> have not yet paid the <b>40 BDT</b> fee for the RFID Attendance Punch Card. "
        "System Administrator <b>Mohammad Shuaib-Ud-Dowla (CE25045)</b> is excluded as requested. "
        "Please settle dues with the batch management team to activate and collect your card."
    )
    notice_p = Paragraph(notice_text, note_style)
    notice_table = Table([[notice_p]], colWidths=[523])
    notice_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, 0), colors.HexColor("#FEF3C7")),
        ('BOX', (0, 0), (0, 0), 0.75, colors.HexColor("#FCD34D")),
        ('TOPPADDING', (0, 0), (0, 0), 3.5),
        ('BOTTOMPADDING', (0, 0), (0, 0), 3.5),
        ('LEFTPADDING', (0, 0), (0, 0), 8),
        ('RIGHTPADDING', (0, 0), (0, 0), 8),
    ]))
    story.append(notice_table)
    story.append(Spacer(1, 6))

    # Section 1: Unpaid Students Table
    story.append(Paragraph(f"Unpaid Students Roster &bull; {len(unpaid_students)} Students Outstanding (Except Admin)", h2_style))

    # Width: 523 total
    # Sl: 20, ID: 58, Name: 155, Email: 140, Phone: 68, Due: 44, Status: 38 = 523
    unpaid_rows = [
        [
            Paragraph("<b>#</b>", th_style),
            Paragraph("<b>Student ID</b>", th_style),
            Paragraph("<b>Student Full Name</b>", th_style),
            Paragraph("<b>Official MBSTU Email</b>", th_style),
            Paragraph("<b>Contact No.</b>", th_style),
            Paragraph("<b>Due</b>", th_style),
            Paragraph("<b>Status</b>", th_style),
        ]
    ]

    for idx, p in enumerate(unpaid_students, 1):
        sid = clean_text(p.get('student_id') or 'N/A')
        name = clean_text(p.get('full_name') or 'N/A')
        email = clean_text(p.get('email') or 'N/A')
        mobile = clean_text(p.get('mobile_number') or '-')
        if mobile.startswith('+88'):
            mobile = mobile[3:]

        rfid_tag = p.get('rfid_card_uid')
        if rfid_tag:
            name += f" <font size='6' color='#64748B'>[Tag: {rfid_tag}]</font>"

        unpaid_rows.append([
            Paragraph(f"<font color='#64748B'>{idx}</font>", body_center),
            Paragraph(f"<b>{sid}</b>", body_center),
            Paragraph(name, body_style),
            Paragraph(email, body_style),
            Paragraph(mobile, body_center),
            Paragraph("<b>40 BDT</b>", body_center),
            Paragraph("UNPAID", badge_unpaid),
        ])

    unpaid_table = Table(
        unpaid_rows,
        colWidths=[20, 58, 155, 140, 68, 44, 38],
        repeatRows=1
    )
    unpaid_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), c_navy),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('TOPPADDING', (0, 0), (-1, -1), 2.2),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 2.2),
        ('LEFTPADDING', (0, 0), (-1, -1), 4),
        ('RIGHTPADDING', (0, 0), (-1, -1), 4),
        ('BOX', (0, 0), (-1, -1), 0.75, c_border),
        ('INNERGRID', (0, 0), (-1, -1), 0.4, c_border),
        *[('BACKGROUND', (0, i), (-1, i), colors.HexColor("#F8FAFC") if i % 2 == 1 else colors.white)
          for i in range(1, len(unpaid_rows))]
    ]))

    story.append(unpaid_table)

    # Page Break for Paid Verification Roster & Reconciliation
    story.append(PageBreak())

    # Section 2: Reference List of Paid Students
    story.append(Paragraph(f"Paid Verification Roster &bull; {len(paid_students)} Students Completed", h2_style))
    story.append(Paragraph("For transparency and payment verification, the 25 students below have fully settled their attendance card fees:", subtitle_style))
    story.append(Spacer(1, 6))

    paid_rows = [
        [
            Paragraph("<b>#</b>", th_style),
            Paragraph("<b>Student ID</b>", th_style),
            Paragraph("<b>Student Full Name</b>", th_style),
            Paragraph("<b>Official MBSTU Email</b>", th_style),
            Paragraph("<b>Paid Amount</b>", th_style),
            Paragraph("<b>Status</b>", th_style),
        ]
    ]

    for idx, p in enumerate(paid_students, 1):
        sid = clean_text(p.get('student_id') or 'N/A')
        name = clean_text(p.get('full_name') or 'N/A')
        email = clean_text(p.get('email') or 'N/A')
        amt = p.get('paid_amount', 40.0)
        cr_badge = " <b>[CR]</b>" if p.get('is_cr') else ""

        paid_rows.append([
            Paragraph(f"<font color='#64748B'>{idx}</font>", body_center),
            Paragraph(f"<b>{sid}</b>", body_center),
            Paragraph(name + cr_badge, body_style),
            Paragraph(email, body_style),
            Paragraph(f"<b>BDT {amt:,.0f}</b>", body_center),
            Paragraph("PAID", badge_paid),
        ])

    paid_table = Table(
        paid_rows,
        colWidths=[24, 76, 175, 148, 55, 45],
        repeatRows=1
    )
    paid_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#065F46")),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('TOPPADDING', (0, 0), (-1, -1), 2.5),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 2.5),
        ('LEFTPADDING', (0, 0), (-1, -1), 4),
        ('RIGHTPADDING', (0, 0), (-1, -1), 4),
        ('BOX', (0, 0), (-1, -1), 0.75, c_border),
        ('INNERGRID', (0, 0), (-1, -1), 0.4, c_border),
        *[('BACKGROUND', (0, i), (-1, i), colors.HexColor("#F0FDF4") if i % 2 == 1 else colors.white)
          for i in range(1, len(paid_rows))]
    ]))
    story.append(paid_table)
    story.append(Spacer(1, 10))

    # Section 3: Admin & System Record
    admin_info = admin_profiles[0] if admin_profiles else {}
    admin_name = clean_text(admin_info.get('full_name') or 'Mohammad Shuaib-Ud-Dowla')
    admin_sid = clean_text(admin_info.get('student_id') or 'CE25045')
    admin_email = clean_text(admin_info.get('email') or 'ce25045@mbstu.ac.bd')

    admin_summary = [
        [
            Paragraph("<b>Batch Administration & System Audit</b>", body_bold),
            Paragraph("<b>Financial Reconciliation Summary</b>", body_bold)
        ],
        [
            Paragraph(
                f"<b>System Administrator:</b> {admin_name} ({admin_sid})<br/>"
                f"<b>Role:</b> App Owner & Class Lead Administrator<br/>"
                f"<b>Email:</b> {admin_email}<br/>"
                f"<b>Audit Note:</b> Excluded from collection list per instruction.",
                body_style
            ),
            Paragraph(
                f"<b>Total Batch Enrollment:</b> 58 Members (57 Students + 1 Admin)<br/>"
                f"<b>Collection Target:</b> 57 Students &times; 40 BDT = 2,280 BDT<br/>"
                f"<b>Total Collected to Date:</b> 1,005 BDT ({len(paid_students)} Students)<br/>"
                f"<b>Total Outstanding Dues:</b> 1,280 BDT (32 Students &times; 40 BDT)",
                body_style
            )
        ]
    ]
    admin_table = Table(admin_summary, colWidths=[260, 263])
    admin_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), c_light_bg),
        ('BOX', (0, 0), (-1, -1), 0.75, c_border),
        ('INNERGRID', (0, 0), (-1, -1), 0.5, c_border),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
    ]))
    story.append(admin_table)

    doc.build(story, canvasmaker=NumberedCanvas)
    print(f"[SUCCESS] PDF generated successfully: {output_path}")


if __name__ == '__main__':
    target_filename = "Attendance_Card_Unpaid_Students_List.pdf"
    root_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', target_filename))
    deliverables_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'deliverables', target_filename))

    print(f"Building publication-ready A4 PDF for Attendance Card Unpaid List...")
    build_pdf(root_path)

    # Also copy to deliverables
    shutil.copy2(root_path, deliverables_path)
    print(f"[SUCCESS] Copied to deliverables: {deliverables_path}")
