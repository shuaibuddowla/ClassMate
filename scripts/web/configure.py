"""Configure local web builds without printing keys. Uses existing CLI credentials."""
import argparse,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'capacity'))
from platform_access import api_keys,STAGING,PRODUCTION,management,query
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('environment',choices=['staging','production']);args=parser.parse_args()
ref=STAGING if args.environment=='staging' else PRODUCTION
firebase=json.loads((ROOT/'build/firebase-web-public.json').read_text(encoding='utf-8-sig'))['result']['sdkConfig']
vapid_path=ROOT/'build/firebase-vapid-public.txt'
vapid=vapid_path.read_text().strip() if vapid_path.exists() else ''
env=f'NEXT_PUBLIC_SUPABASE_URL=https://{ref}.supabase.co\nNEXT_PUBLIC_SUPABASE_ANON_KEY={api_keys(ref)["anon"]}\nNEXT_PUBLIC_FIREBASE_CONFIG={json.dumps(firebase,separators=(",",":"))}\nNEXT_PUBLIC_FIREBASE_VAPID_KEY={vapid}\n'
(ROOT/'web/.env.local').write_text(env,encoding='utf-8')
print('Configured web for '+args.environment+'; public Firebase VAPID key '+('present' if vapid else 'not configured'))
