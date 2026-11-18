import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

candidates = [
    '/api/notices-news',
    '/api/notices',
    '/api/bus-data',
    '/api/buses',
    '/api/bus/all',
    '/api/gps',
    '/api/live-bus',
    '/api/live-tracking',
    '/api/vehicles',
    '/api/drivers',
    '/api/schedule',
    '/api/bus-schedule'
]

for c in candidates:
    url = 'https://transport.mbstu.ac.bd' + c
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=4) as res:
            print(f"[{res.status}] {c}: {res.read()[:200]}")
    except urllib.error.HTTPError as e:
        print(f"[{e.code}] {c}")
    except Exception as e:
        print(f"[ERR] {c}: {e}")
