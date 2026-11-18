import sys
from pathlib import Path
sys.path.insert(0, str(Path("scripts/capacity")))
import platform_access
import urllib.request
import os

keys = platform_access.api_keys(platform_access.PRODUCTION)
service_key = keys.get("service_role", keys.get("anon"))
print("Got service key:", bool(service_key))

apk_path = Path("app/build/outputs/apk/release/app-release.apk")
if not apk_path.exists():
    print("APK does not exist at", apk_path)
    sys.exit(1)

apk_bytes = apk_path.read_bytes()
size_mb = len(apk_bytes) / (1024 * 1024)
print(f"Read APK: {len(apk_bytes)} bytes ({size_mb:.2f} MB)")

object_name = "ClassMate-release-v1.1.45.apk"
url = f"https://{platform_access.PRODUCTION}.supabase.co/storage/v1/object/apk-builds/{object_name}"

req = urllib.request.Request(
    url,
    data=apk_bytes,
    method="POST",
    headers={
        "Authorization": f"Bearer {service_key}",
        "apikey": service_key,
        "Content-Type": "application/vnd.android.package-archive",
        "x-upsert": "true"
    }
)

print(f"Uploading to {url}...")
try:
    with urllib.request.urlopen(req, timeout=120) as resp:
        res = resp.read().decode("utf-8")
        print("Upload success:", res)
        public_url = f"https://{platform_access.PRODUCTION}.supabase.co/storage/v1/object/public/apk-builds/{object_name}"
        print("Public download URL:", public_url)
except urllib.error.HTTPError as e:
    print(f"HTTP Error {e.code}: {e.read().decode('utf-8')}")
except Exception as e:
    print(f"Error: {e}")
