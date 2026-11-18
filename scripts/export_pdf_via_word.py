import os

out_docx = r"D:\Documents\ClassMate_Project_Showcase_Report_Updated.docx"
out_pdf = r"D:\Documents\ClassMate_Project_Showcase_Report_Updated.pdf"

try:
    import win32com.client
    word = win32com.client.Dispatch("Word.Application")
    doc = word.Documents.Open(os.path.abspath(out_docx))
    doc.SaveAs(os.path.abspath(out_pdf), FileFormat=17) # 17 = wdFormatPDF
    doc.Close()
    print("PDF generated via Word COM successfully! Size:", os.path.getsize(out_pdf)/1024, "KB")
except Exception as e:
    print("Word COM PDF export note:", e)
