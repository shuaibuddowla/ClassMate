import sys
from pathlib import Path
sys.path.insert(0, str(Path("scripts/capacity")))
import urllib.request
import json

anon_key = "sb_publishable_YcAvoJqDZQ06XZobEueGoA_mTgiGtNL"

apk_path = Path("app/build/outputs/apk/release/app-release.apk")
data = apk_path.read_bytes()
print(f"Uploading {len(data)} bytes...")

object_name = "ClassMate-release-v1.1.45-verification.apk"
url = f"https://cbskpifgbdbgdmnnxfrh.supabase.co/storage/v1/object/apk-builds/{object_name}"

req = urllib.request.Request(
    url,
    data=data,
    method="POST",
    headers={
        "Authorization": f"Bearer {anon_key}",
        "apikey": anon_key,
        "Content-Type": "application/vnd.android.package-archive",
        "x-upsert": "true"
    }
)

try:
    with urllib.request.urlopen(req, timeout=120) as r:
        print("Success:", r.read().decode())
        print("Download URL:")
        print(f"https://cbskpifgbdbgdmnnxfrh.supabase.co/storage/v1/object/public/apk-builds/{object_name}")
except urllib.error.HTTPError as e:
    print(f"HTTP {e.code}: {e.read().decode()}")
except Exception as e:
    print("Error:", e)
