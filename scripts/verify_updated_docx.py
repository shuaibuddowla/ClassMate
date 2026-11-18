import docx

doc = docx.Document(r"D:\Documents\ClassMate_Project_Showcase_Report_Updated.docx")
body = doc._body._element

print("Total paragraphs in updated docx:", len(doc.paragraphs))
print("Total tables in updated docx:", len(doc.tables))

for i, child in enumerate(body):
    tag = child.tag.split("}")[-1]
    if tag == "p":
        txt = child.xpath("string(.)").strip()[:50]
        if txt:
            print(f"{i}: <p> {txt}")
    elif tag == "tbl":
        c_text = child.xpath("string(.//w:tc[1])")[:40].strip()
        print(f"{i}: <tbl> {c_text} (rows={len(child.xpath('.//w:tr'))})")
