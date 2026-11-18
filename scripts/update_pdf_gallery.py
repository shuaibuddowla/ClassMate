import re

with open("scripts/generate_showcase_report.py", "r", encoding="utf-8") as f:
    text = f.read()

old_gallery_pattern = r'    # Row 1: Academic Management \(4 screenshots\).*?story\.append\(grid_row2\)'

new_gallery = '''    # Asset paths (5 final screenshots)
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
    story.append(grid_row2)'''

new_text, n = re.subn(old_gallery_pattern, new_gallery, text, flags=re.DOTALL)
print(f"Replaced gallery in generate_showcase_report.py: {n}")

with open("scripts/generate_showcase_report.py", "w", encoding="utf-8") as f:
    f.write(new_text)
