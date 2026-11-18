import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

base = "https://transport.mbstu.ac.bd"

test_paths = [
    "",
    "/",
    "/MBSTU-Wheels",
    "/MBSTU-Wheels/Home",
    "/MBSTU-Wheels/Dashboard",
    "/MBSTU-Wheels/Live",
    "/MBSTU-Wheels/Tracking",
    "/MBSTU-Wheels/LiveTracking",
    "/MBSTU-Wheels/Schedule",
    "/MBSTU-Wheels/BusSchedule",
    "/MBSTU-Wheels/Buses",
    "/MBSTU-Wheels/Notice",
    "/MBSTU-Wheels/Emergency",
    "/MBSTU-Wheels/Ambulance",
    "/MBSTU-Wheels/User",
    "/MBSTU-Wheels/Profile",
    "/MBSTU-Wheels/Admin",
    "/api/buses",
    "/api/bus",
    "/api/schedule",
    "/api/routes",
    "/api/live",
    "/api/tracking",
    "/api/vehicles",
    "/api/sign-in-status/"
]

for p in test_paths:
    url = base + p
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=5) as res:
            final_url = res.geturl()
            content = res.read(500).decode('utf-8', errors='ignore')
            print(f"[{res.status}] {p} -> {final_url} (len: {len(content)})")
    except urllib.error.HTTPError as e:
        print(f"[{e.code}] {p}")
    except Exception as e:
        print(f"[ERR] {p}: {e}")
