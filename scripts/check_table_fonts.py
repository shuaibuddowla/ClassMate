import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
for i in [2, 3, 4, 5]:
    t = doc.tables[i]
    cell = t.rows[0].cells[0]
    p = cell.paragraphs[0]
    print(f'Table {i}: first cell text = {p.text}')
    if p.runs:
        r = p.runs[0]
        print(f'   Header font: {r.font.name}, size={r.font.size}, color={r.font.color.rgb if r.font.color else None}')
    cell_body = t.rows[1].cells[0]
    pb = cell_body.paragraphs[0]
    if pb.runs:
        rb = pb.runs[0]
        print(f'   Body font: {rb.font.name}, size={rb.font.size}, color={rb.font.color.rgb if rb.font.color else None}')
