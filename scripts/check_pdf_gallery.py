import os

with open("scripts/generate_showcase_report.py", "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, l in enumerate(lines):
    if "SECTION 4: PRODUCTION USER INTERFACE" in l:
        for j in range(i-2, min(len(lines), i+80)):
            print(f"{j+1}: {lines[j]}", end="")
        break
