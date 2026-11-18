with open("scripts/generate_showcase_report.py", "r", encoding="utf-8") as f:
    lines = f.readlines()

for j in range(370, 465):
    print(f"{j+1}: {lines[j]}", end="")
