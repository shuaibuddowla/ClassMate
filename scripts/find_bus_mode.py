with open('web/src/components/schedule.tsx', 'r', encoding='utf-8') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'mode === "bus"', text)]
print("Matches:", len(matches))
for idx in matches:
    print("--- MATCH ---")
    print(text[idx:idx+1000])
