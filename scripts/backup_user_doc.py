import shutil, os

doc_path = r"D:\Documents\ClassMate_Project_Showcase_Report.docx"
backup_path = r"D:\Documents\ClassMate_Project_Showcase_Report_backup.docx"

shutil.copy2(doc_path, backup_path)
print(f"Backup created at: {backup_path} ({os.path.getsize(backup_path)/1024:.1f} KB)")
