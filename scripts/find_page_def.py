import os
import re

chunk_dir = "scratch/mbstu_chunks"
files = [os.path.join(chunk_dir, f) for f in os.listdir(chunk_dir) if f.endswith(".js")]

for f in files:
    with open(f, "r", encoding="utf-8", errors="ignore") as fp:
        c = fp.read()
    # Check if this file defines page for /MBSTU-Wheels
    for match in re.finditer(r'(["\']/[^"\']*MBSTU-Wheels[^"\']*["\'])', c):
        print(f"In {f}: {match.group(1)}")
