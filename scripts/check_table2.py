import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
t = doc.tables[2]
print(f'Table 2 rows: {len(t.rows)}, cols: {len(t.columns)}')
for r_i, r in enumerate(t.rows):
    row_texts = [c.text.strip().replace('\n', ' ') for c in r.cells]
    print(f'Row {r_i}: {row_texts}')
