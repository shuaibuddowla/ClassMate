"""Deploy or check bundle for classmate-ai Edge Function."""
import sys, json, os, urllib.request, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts/capacity'))
from platform_access import access_token, PRODUCTION, STAGING

token = access_token()
env = sys.argv[1] if len(sys.argv) > 1 else 'staging'
ref = STAGING if env == 'staging' else PRODUCTION
real_deploy = '--live' in sys.argv

files = [
    ('supabase/functions/classmate-ai/index.ts', (root / 'supabase/functions/classmate-ai/index.ts').read_text(encoding='utf-8')),
    ('supabase/functions/classmate-ai/planning.ts', (root / 'supabase/functions/classmate-ai/planning.ts').read_text(encoding='utf-8')),
    ('supabase/functions/classmate-ai/provider.ts', (root / 'supabase/functions/classmate-ai/provider.ts').read_text(encoding='utf-8')),
    ('supabase/functions/classmate-ai/tools.ts', (root / 'supabase/functions/classmate-ai/tools.ts').read_text(encoding='utf-8')),
    ('supabase/functions/_shared/web-cors.ts', (root / 'supabase/functions/_shared/web-cors.ts').read_text(encoding='utf-8')),
]

boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW'
parts = []
metadata = {
    'entrypoint_path': 'supabase/functions/classmate-ai/index.ts',
    'name': 'classmate-ai',
    'verify_jwt': False
}
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n{json.dumps(metadata)}\r\n')

for filename, content in files:
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/typescript\r\n\r\n{content}\r\n')

parts.append(f'--{boundary}--\r\n')
body = ''.join(parts).encode('utf-8')

url = f'https://api.supabase.com/v1/projects/{ref}/functions/deploy?slug=classmate-ai'
if not real_deploy:
    url += '&bundleOnly=1'

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
        print(f'{env} {"LIVE DEPLOY" if real_deploy else "bundleOnly check"}:', resp.status, resp.read().decode())
except urllib.error.HTTPError as e:
    print(f'{env} error:', e.code, e.read().decode())
