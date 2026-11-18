import urllib.request
import ssl
import re
import json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

base_url = "https://transport.mbstu.ac.bd"

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
    with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
        return res.read().decode('utf-8', errors='ignore')

html = fetch(base_url + "/MBSTU-Wheels")
print("Title and Meta:")
for tag in re.findall(r'<title>(.*?)</title>', html, re.I):
    print("Title:", tag)

scripts = re.findall(r'src="([^"]+\.js)"', html)
all_routes = set()
all_apis = set()

for s in scripts:
    url = base_url + s if s.startswith("/") else base_url + "/" + s
    try:
        content = fetch(url)
        for r in re.findall(r'["\'](/MBSTU-Wheels[a-zA-Z0-9_\-\/]*)["\']', content):
            all_routes.add(r)
        for api in re.findall(r'["\'](/api/[a-zA-Z0-9_\-\/]+)["\']', content):
            all_apis.add(api)
        for m in re.findall(r'https?://[a-zA-Z0-9_\-\.]+(?:firebaseio|supabase|firebase|herokuapp|vercel|render|mbstu|onrender)[a-zA-Z0-9_\-\.\:\/]*', content):
            if not m.endswith(".woff2") and not m.endswith(".js") and not m.endswith(".png"):
                print("Backend / URL ref:", m)
    except Exception as e:
        print("Err fetching script", s, e)

print("\nRoutes found in JS chunks:")
for r in sorted(all_routes):
    print(" ", r)

print("\nAPIs found in JS chunks:")
for a in sorted(all_apis):
    print(" ", a)
