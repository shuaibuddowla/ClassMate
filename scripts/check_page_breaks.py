import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
for i, p in enumerate(doc.paragraphs):
    for r in p.runs:
        if 'lastRenderedPageBreak' in r._r.xml or 'w:br' in r._r.xml:
            print(f'Paragraph {i} has page break: text={p.text[:30]}')
