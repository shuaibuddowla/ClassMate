import urllib.request
import json

SUPABASE_URL = 'https://cbskpifgbdbgdmnnxfrh.supabase.co'
SERVICE_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNic2twaWZnYmRiZ2Rtbm54ZnJoIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc5MDIzMjAwNCwiZXhwIjoyMTA1ODA4MDA0fQ.a4IYuyRvpKdYh6roMcGuVHC0DHaUcdBTuxvxLEg1JN8'
headers = {
    'apikey': SERVICE_KEY,
    'Authorization': f'Bearer {SERVICE_KEY}',
    'Accept-Profile': 'classmate',
    'Content-Profile': 'classmate',
}

# Check batch_overrides
req = urllib.request.Request(f'{SUPABASE_URL}/rest/v1/batch_overrides?select=*', headers=headers)
with urllib.request.urlopen(req) as resp:
    overrides = json.loads(resp.read().decode('utf-8'))
print('Batch overrides:', json.dumps(overrides, indent=2))

# Check batches
req_b = urllib.request.Request(f'{SUPABASE_URL}/rest/v1/batches?select=*', headers=headers)
with urllib.request.urlopen(req_b) as resp:
    batches = json.loads(resp.read().decode('utf-8'))
b_map = {b['id']: b for b in batches}
print('Batches:')
for b in batches:
    print(f"  {b['id']} | batch_number: {b.get('batch_number')} | name: {b.get('name')}")

# Check profiles for students in batch
req2 = urllib.request.Request(f'{SUPABASE_URL}/rest/v1/profiles?role=eq.student&select=id,full_name,student_id,batch_id&order=student_id.asc', headers=headers)
with urllib.request.urlopen(req2) as resp:
    students = json.loads(resp.read().decode('utf-8'))
print(f'\nTotal students: {len(students)}')
for s in students:
    b = b_map.get(s.get('batch_id'), {})
    b_num = b.get('batch_number')
    print(f"  {s['student_id']} | {s['full_name']} | batch_number: {b_num} ({s.get('batch_id')})")
