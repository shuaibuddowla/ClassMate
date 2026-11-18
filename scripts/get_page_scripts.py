import urllib.request
import ssl
import re

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
        return res.read().decode('utf-8', errors='ignore')

for p in ['/Schedule', '/Vehicles', '/Notices-News', '/Transport-Directory']:
    html = fetch('https://transport.mbstu.ac.bd' + p)
    scripts = re.findall(r'src="([^"]+\.js)"', html)
    print(f"\n--- {p} ({len(scripts)} scripts) ---")
    for s in scripts:
        print("  ", s)
