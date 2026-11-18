import docx

doc = docx.Document(r'D:\Documents\ClassMate_Project_Showcase_Report.docx')
body = doc._body._element
for i, child in enumerate(body):
    tag = child.tag.split('}')[-1]
    if tag == 'p':
        p_text = child.xpath('string(.)')[:60].strip()
        print(f'{i}: <p> {p_text}')
    elif tag == 'tbl':
        c_text = child.xpath('string(.//w:tc[1])')[:40].strip()
        print(f'{i}: <tbl> {c_text}')
    else:
        print(f'{i}: <{tag}>')
