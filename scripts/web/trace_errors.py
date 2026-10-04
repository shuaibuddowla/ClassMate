"""Print failed API statuses without request headers, tokens or signed URLs."""
import json,sys,zipfile,urllib.parse
z=zipfile.ZipFile(sys.argv[1])
for name in z.namelist():
 if not name.endswith('.network'):continue
 for line in z.read(name).decode().splitlines():
  data=json.loads(line).get('snapshot',{});response=data.get('response',{})
  if response.get('status',200)<400:continue
  path=urllib.parse.urlparse(data.get('request',{}).get('url','')).path
  print(path,response.get('status'))
  content=response.get('content',{}).get('_sha1')
  if content and path.startswith('/functions/'):
   print(z.read('resources/'+content).decode()[:1000])
