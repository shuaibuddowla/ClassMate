import ctypes,ctypes.wintypes as w,json,urllib.request,re,os
class Credential(ctypes.Structure):
 _fields_=[('Flags',w.DWORD),('Type',w.DWORD),('TargetName',w.LPWSTR),('Comment',w.LPWSTR),('LastWritten',w.FILETIME),('CredentialBlobSize',w.DWORD),('CredentialBlob',ctypes.POINTER(ctypes.c_byte)),('Persist',w.DWORD),('AttributeCount',w.DWORD),('Attributes',ctypes.c_void_p),('TargetAlias',w.LPWSTR),('UserName',w.LPWSTR)]
def access_token():
 if os.environ.get("SUPABASE_ACCESS_TOKEN"): return os.environ["SUPABASE_ACCESS_TOKEN"]
 ptr=ctypes.POINTER(Credential)()
 if not ctypes.windll.advapi32.CredReadW('Supabase CLI:supabase',1,0,ctypes.byref(ptr)): raise RuntimeError('CLI credential unavailable')
 try:
  blob=ctypes.string_at(ptr.contents.CredentialBlob,ptr.contents.CredentialBlobSize)
  for encoding in ('utf-8','utf-16-le'):
   try:
    value=blob.decode(encoding).strip('\x00')
    if value.startswith('sbp_'):return value
   except UnicodeError:pass
  raise RuntimeError('Unrecognized credential format')
 finally:ctypes.windll.advapi32.CredFree(ptr)
def management(path,method='GET',body=None):
 req=urllib.request.Request('https://api.supabase.com/v1/'+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers={'Authorization':'Bearer '+access_token(),'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)

STAGING="qpryxvunhebwljlwmggk"
PRODUCTION="cbskpifgbdbgdmnnxfrh"
def query(ref,sql):return management('projects/'+ref+'/database/query','POST',{'query':sql})
def api_keys(ref):
 keys=management('projects/'+ref+'/api-keys')
 return {item['name']:item['api_key'] for item in keys if 'api_key' in item}
