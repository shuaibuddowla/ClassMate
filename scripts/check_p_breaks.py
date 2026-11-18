import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
for i in [4, 21, 23, 26]:
    p = doc.paragraphs[i]
    print(f'P{i} ({p.text[:30]}): page_break_before={p.paragraph_format.page_break_before}, xml={p._p.xml[:120]}')
