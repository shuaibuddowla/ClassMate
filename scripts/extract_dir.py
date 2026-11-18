with open("scratch/mbstu_chunks/d5a9eac3751c3743.js", "r", encoding="utf-8") as f:
    text = f.read()

import re
idx = text.find('Transport Committee')
if idx != -1:
    start = max(0, idx - 100)
    end = min(len(text), idx + 4000)
    print(text[start:end])
