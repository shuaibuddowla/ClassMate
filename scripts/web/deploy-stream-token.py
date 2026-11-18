import sys, json, os, urllib.request, pathlib
root = pathlib.Path('.').resolve()
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import access_token, PRODUCTION, STAGING

token = access_token()
files = [
    ('supabase/functions/stream-token/index.ts', (root / 'supabase/functions/stream-token/index.ts').read_text(encoding='utf-8')),
    ('supabase/functions/_shared/web-cors.ts', (root / 'supabase/functions/_shared/web-cors.ts').read_text(encoding='utf-8')),
]
boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW'
parts = []
metadata = {
    'entrypoint_path': 'supabase/functions/stream-token/index.ts',
    'name': 'stream-token',
    'verify_jwt': False
}
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n{json.dumps(metadata)}\r\n')
for filename, content in files:
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/typescript\r\n\r\n{content}\r\n')
parts.append(f'--{boundary}--\r\n')
body = ''.join(parts).encode('utf-8')

target_envs = [sys.argv[1]] if len(sys.argv) > 1 else ['production']
for env in target_envs:
    ref = STAGING if env == 'staging' else PRODUCTION
    url = f'https://api.supabase.com/v1/projects/{ref}/functions/deploy?slug=stream-token'
    req = urllib.request.Request(
        url,
        headers={'Authorization': f'Bearer {token}', 'Content-Type': f'multipart/form-data; boundary={boundary}'},
        data=body, method='POST'
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f'Deploy stream-token to {env}:', resp.status, resp.read().decode('utf-8')[:150])
    except urllib.error.HTTPError as e:
        print(f'Error deploying to {env}:', e.code, e.read().decode('utf-8')[:150])
