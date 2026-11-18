import json
import re

with open("scratch/mbstu_chunks/d5cfee3f4d27479c.js", "r", encoding="utf-8") as f:
    text = f.read()

# Search for the vehicles array
print("Searching vehicles in d5cfee3f4d27479c.js...")
matches = re.findall(r'\[\{title:"[^"]+",vehicles:[^\]]+\]', text)
print(f"Matches: {len(matches)}")

# Let's search for bus titles / names
names = re.findall(r'[\u0980-\u09FFa-zA-Z0-9\s]+', text)
bn_names = [n.strip() for n in names if any('\u0980' <= ch <= '\u09FF' for ch in n) and len(n.strip()) > 2]
print("Bengali names found:", set(bn_names))

# Print chunks of text containing 'Student Buses' or 'Teacher Buses'
for m in re.finditer(r'title:"(Student Buses|Teacher Buses|Officer Buses|Ambulance|Other Vehicles)"', text):
    start = max(0, m.start() - 50)
    end = min(len(text), m.end() + 1500)
    print("\n--- SECTION ---")
    print(text[start:end])
