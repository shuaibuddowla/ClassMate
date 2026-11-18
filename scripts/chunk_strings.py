import os
import re

chunk_dir = "scratch/mbstu_chunks"
files = [os.path.join(chunk_dir, f) for f in os.listdir(chunk_dir) if f.endswith(".js")]

for f in files:
    with open(f, "r", encoding="utf-8", errors="ignore") as fp:
        c = fp.read()
    print(f"=== {f} ({len(c)} bytes) ===")
    # Look for JSX/strings
    matches = re.findall(r'["\']([A-Z][a-zA-Z0-9\s]{3,40})["\']', c)
    important = [m for m in matches if any(w in m.lower() for w in ['wheel', 'bus', 'track', 'student', 'driver', 'schedule', 'password', 'login', 'token', 'route', 'map', 'vehicle'])]
    print("Interesting strings:", list(set(important))[:20])
