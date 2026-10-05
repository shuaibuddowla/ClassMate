"""Staging-only rollback checks; never sends a real test alert."""
import sys,pathlib,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query,STAGING
try:query(STAGING,(root/'supabase/tests/classmate_blood_network.sql').read_text(encoding='utf-8'))
except urllib.error.HTTPError as e:print(e.read().decode());raise
print('Blood network staging authorization, compatibility, consent, cooldown and queue checks passed; fixtures rolled back.')
