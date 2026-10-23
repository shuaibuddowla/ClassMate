"""Deploy upload-classmate-resource Edge Function."""
import sys, json, os, urllib.request, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import access_token, PRODUCTION, STAGING

token = access_token()

files = [
    ('supabase/functions/upload-classmate-resource/index.ts', (root / 'supabase/functions/upload-classmate-resource/index.ts').read_text(encoding='utf-8')),
    ('supabase/functions/_shared/web-cors.ts', (root / 'supabase/functions/_shared/web-cors.ts').read_text(encoding='utf-8')),
]

boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW'
parts = []
metadata = {
    'entrypoint_path': 'supabase/functions/upload-classmate-resource/index.ts',
    'name': 'upload-classmate-resource',
    'verify_jwt': False
}
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n{json.dumps(metadata)}\r\n')

for filename, content in files:
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/typescript\r\n\r\n{content}\r\n')

parts.append(f'--{boundary}--\r\n')
body = ''.join(parts).encode('utf-8')

target_envs = [sys.argv[1]] if len(sys.argv) > 1 else ['staging', 'production']
for env in target_envs:
    ref = STAGING if env == 'staging' else PRODUCTION
    print(f"\nDeploying upload-classmate-resource to {env} ({ref})...")
    url = f'https://api.supabase.com/v1/projects/{ref}/functions/deploy?slug=upload-classmate-resource'
    req = urllib.request.Request(
        url,
        headers={
            'Authorization': f'Bearer {token}',
            'Content-Type': f'multipart/form-data; boundary={boundary}'
        },
        data=body,
        method='POST'
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"Success on {env}! Status: {resp.status}, response: {resp.read().decode('utf-8')[:120]}")
    except urllib.error.HTTPError as e:
        print(f"HTTPError on {env}: {e.code} {e.reason}: {e.read().decode('utf-8')}")
    except Exception as e:
        print(f"Error on {env}: {e}")
