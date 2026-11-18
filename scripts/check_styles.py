import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
for p in [doc.paragraphs[4], doc.paragraphs[5], doc.paragraphs[21]]:
    print(f'Text: {p.text[:40]} | Style: {p.style.name} | SpaceAfter: {p.paragraph_format.space_after}')
    for r in p.runs[:2]:
        print(f'   Run: \"{r.text}\", font={r.font.name}, size={r.font.size}, bold={r.font.bold}, color={r.font.color.rgb if r.font.color else None}')
