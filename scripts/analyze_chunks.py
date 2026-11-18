import os
import re
import sys

chunk_dir = "scratch/mbstu_chunks"
files = [os.path.join(chunk_dir, f) for f in os.listdir(chunk_dir) if f.endswith(".js")]

all_endpoints = set()
for fpath in files:
    with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()
        for m in re.findall(r'["\'](/api/[^"\'\s]+)["\']', content):
            all_endpoints.add(m)
        for m in re.findall(r'["\'](/MBSTU-Wheels[^"\'\s]*)["\']', content):
            all_endpoints.add(m)
        for m in re.findall(r'https?://[a-zA-Z0-9_\-\.:/]+', content):
            if not any(ext in m for ext in ['.js', '.css', '.woff', '.png', '.jpg', '.svg', 'w3.org']):
                all_endpoints.add(m)

print("Endpoints & URLs found:")
for ep in sorted(all_endpoints):
    try:
        print(" ", ep)
    except Exception:
        pass

# Search for what pages exist in Next.js manifest or routes
for fpath in files:
    with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
        c = f.read()
        # Find page paths
        for page in re.findall(r'["\'](/MBSTU-Wheels/[a-zA-Z0-9_\-\/]+)["\']', c):
            print("Page:", page)
        # Find any fetch or axios calls
        for call in re.findall(r'(?:fetch|axios\.[a-z]+)\s*\(\s*[`"\']([^`"\']+)["\']', c):
            print("API call:", call)
