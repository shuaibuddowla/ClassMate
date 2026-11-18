import urllib.request
import ssl
import re
import os

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

base_url = "https://transport.mbstu.ac.bd"

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
    with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
        return res.read().decode('utf-8', errors='ignore')

html = fetch(base_url + "/MBSTU-Wheels")
scripts = re.findall(r'src="([^"]+\.js)"', html)

os.makedirs("scratch/mbstu_chunks", exist_ok=True)

for s in scripts:
    filename = s.split("/")[-1]
    local_path = os.path.join("scratch/mbstu_chunks", filename)
    url = base_url + s if s.startswith("/") else base_url + "/" + s
    if not os.path.exists(local_path):
        try:
            print("Fetching", filename)
            data = fetch(url)
            with open(local_path, "w", encoding="utf-8") as f:
                f.write(data)
        except Exception as e:
            print("Failed", s, e)

print("Downloaded all scripts to scratch/mbstu_chunks")
