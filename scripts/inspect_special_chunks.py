import urllib.request
import ssl
import json
import re

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
        return res.read().decode('utf-8', errors='ignore')

scripts = [
    '7c31a16e70ab824d.js',
    'd5cfee3f4d27479c.js',
    '32bb5e4e2e943e25.js',
    'ea6c7b9cabd4cd1b.js',
    'd5a9eac3751c3743.js'
]

for s in scripts:
    url = f"https://transport.mbstu.ac.bd/_next/static/chunks/{s}"
    try:
        content = fetch(url)
        print(f"\n=================== {s} ({len(content)} bytes) ===================")
        # Search for APIs, URLs, data objects
        apis = re.findall(r'https?://[^\s"\'<>]+|/api/[^\s"\'<>]+', content)
        print("APIs / URLs:", list(set(apis)))
        # Search for data structures
        lines = [line.strip() for line in content.split(";") if any(k in line for k in ['schedule', 'bus', 'vehicles', 'notice', 'phone', 'driver', 'route'])]
        print(f"Key code fragments ({len(lines)}):")
        for l in lines[:10]:
            print("  *", l[:150])
    except Exception as e:
        print("Error fetching", s, e)
