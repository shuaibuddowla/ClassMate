import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
print(doc.paragraphs[4]._p.xml)
