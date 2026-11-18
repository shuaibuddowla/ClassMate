import sys, json, os, urllib.request, pathlib
root = pathlib.Path('.').resolve()
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import access_token, PRODUCTION

token = access_token()
files = [
    ('supabase/functions/send-notification/index.ts', (root / 'supabase/functions/send-notification/index.ts').read_text(encoding='utf-8')),
]
boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW'
parts = []
metadata = {
    'entrypoint_path': 'supabase/functions/send-notification/index.ts',
    'name': 'send-notification',
    'verify_jwt': False
}
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n{json.dumps(metadata)}\r\n')
for filename, content in files:
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/typescript\r\n\r\n{content}\r\n')
parts.append(f'--{boundary}--\r\n')
body = ''.join(parts).encode('utf-8')

url = f'https://api.supabase.com/v1/projects/{PRODUCTION}/functions/deploy?slug=send-notification'
req = urllib.request.Request(
    url,
    headers={'Authorization': f'Bearer {token}', 'Content-Type': f'multipart/form-data; boundary={boundary}'},
    data=body, method='POST'
)
try:
    with urllib.request.urlopen(req) as resp:
        print('Deploy send-notification status:', resp.status, resp.read().decode('utf-8')[:200])
except urllib.error.HTTPError as e:
    print('Deploy error:', e.code, e.read().decode('utf-8'))
