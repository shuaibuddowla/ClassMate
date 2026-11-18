import os
import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT

def insert_screenshots(docx_path, output_path):
    doc = docx.Document(docx_path)
    
    # Identify colors
    c_primary = RGBColor(0x1E, 0x3A, 0x8A) # Deep Navy
    c_slate = RGBColor(0x47, 0x55, 0x69)   # Slate gray
    
    # Find paragraph 25 (Heading 4) and paragraph 9 (Intro)
    body = doc._body._element
    p_intro = None
    p_h4 = None
    empty_ps = []
    
    for p in doc.paragraphs:
        txt = p.text.strip()
        if "Production screenshots from the live ClassMate" in txt:
            p_intro = p
        elif "4. Comprehensive Technology Stack" in txt:
            p_h4 = p
        elif p_intro is not None and p_h4 is None:
            if not txt:
                empty_ps.append(p)
                
    print(f"Found p_intro: {p_intro.text[:40] if p_intro else None}")
    print(f"Found p_h4: {p_h4.text[:40] if p_h4 else None}")
    print(f"Found {len(empty_ps)} empty paragraphs between intro and H4")
    
    # Remove empty paragraphs
    for p in empty_ps:
        p._p.getparent().remove(p._p)
        
    # Prepare Row 1 Data (3 images)
    row1 = [
        ("showcase_assets/screen_live_timetable_v2.png", "Live Dynamic Timetable", "Date-aware routine with period countdowns & lab indicators"),
        ("showcase_assets/screen_notice_feed_v2.png", "Official Notices & Telemetry", "Urgent push notices, file attachments & seen-counter"),
        ("showcase_assets/screen_courses_hub_v2.png", "Courses & Resource Hub", "Semester courses, lecture vault & attendance logs")
    ]
    
    # Prepare Row 2 Data (2 images)
    row2 = [
        ("showcase_assets/screen_bus_map_gps_v2.png", "Live Satellite Transit GPS", "Real-time university bus tracking & campus corridor stops"),
        ("showcase_assets/screen_batch_fund_v2.png", "Batch Fund & Transparency", "Class treasury ledger, collection stats & payment logs")
    ]
    
    img_w = Inches(1.48)
    
    # Create Table 1 (3 cols)
    t1 = doc.add_table(rows=2, cols=3)
    t1.alignment = WD_TABLE_ALIGNMENT.CENTER
    t1.autofit = False
    col_w3 = Inches(2.26)
    
    for i, (path, title, desc) in enumerate(row1):
        # Image cell
        c_img = t1.cell(0, i)
        c_img.width = col_w3
        p_img = c_img.paragraphs[0]
        p_img.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p_img.paragraph_format.space_before = Pt(0)
        p_img.paragraph_format.space_after = Pt(2)
        if os.path.exists(path):
            p_img.add_run().add_picture(path, width=img_w)
            
        # Caption cell
        c_cap = t1.cell(1, i)
        c_cap.width = col_w3
        p_cap = c_cap.paragraphs[0]
        p_cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p_cap.paragraph_format.line_spacing = 1.05
        p_cap.paragraph_format.space_before = Pt(1)
        p_cap.paragraph_format.space_after = Pt(2)
        
        r_t = p_cap.add_run(title + "\n")
        r_t.bold = True
        r_t.font.size = Pt(8)
        r_t.font.color.rgb = c_primary
        
        r_d = p_cap.add_run(desc)
        r_d.font.size = Pt(7)
        r_d.font.color.rgb = c_slate
        
    # Create Table 2 (2 cols)
    t2 = doc.add_table(rows=2, cols=2)
    t2.alignment = WD_TABLE_ALIGNMENT.CENTER
    t2.autofit = False
    col_w2 = Inches(3.40)
    
    for i, (path, title, desc) in enumerate(row2):
        # Image cell
        c_img = t2.cell(0, i)
        c_img.width = col_w2
        p_img = c_img.paragraphs[0]
        p_img.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p_img.paragraph_format.space_before = Pt(0)
        p_img.paragraph_format.space_after = Pt(2)
        if os.path.exists(path):
            p_img.add_run().add_picture(path, width=img_w)
            
        # Caption cell
        c_cap = t2.cell(1, i)
        c_cap.width = col_w2
        p_cap = c_cap.paragraphs[0]
        p_cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p_cap.paragraph_format.line_spacing = 1.05
        p_cap.paragraph_format.space_before = Pt(1)
        p_cap.paragraph_format.space_after = Pt(2)
        
        r_t = p_cap.add_run(title + "\n")
        r_t.bold = True
        r_t.font.size = Pt(8)
        r_t.font.color.rgb = c_primary
        
        r_d = p_cap.add_run(desc)
        r_d.font.size = Pt(7)
        r_d.font.color.rgb = c_slate
        
    # Create spacer paragraph between tables
    p_spacer = doc.add_paragraph()
    p_spacer.paragraph_format.space_before = Pt(2)
    p_spacer.paragraph_format.space_after = Pt(4)
    
    # Create page break after Section 3 so Section 4 starts on the next page cleanly
    p_break = doc.add_paragraph()
    p_break.paragraph_format.space_before = Pt(0)
    p_break.paragraph_format.space_after = Pt(0)
    r_br = p_break.add_run()
    r_br.add_break(docx.enum.text.WD_BREAK.PAGE)
    
    # Now reposition t1, p_spacer, t2, p_break before p_h4
    p_h4_elem = p_h4._p
    p_h4_elem.addprevious(t1._tbl)
    p_h4_elem.addprevious(p_spacer._p)
    p_h4_elem.addprevious(t2._tbl)
    p_h4_elem.addprevious(p_break._p)
    
    doc.save(output_path)
    print(f"Saved modified document to {output_path} ({os.path.getsize(output_path)/1024:.1f} KB)")

if __name__ == "__main__":
    insert_screenshots(r"D:\Documents\ClassMate_Project_Showcase_Report.docx", r"D:\Documents\ClassMate_Project_Showcase_Report.docx")
