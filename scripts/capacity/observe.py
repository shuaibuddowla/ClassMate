"""Hosting snapshots and resource guard. No credentials enter reports or the app."""
import argparse,base64,datetime,json,re,time,urllib.request
from pathlib import Path
from platform_access import STAGING,PRODUCTION,management,query,api_keys
def collect(ref,previous=None):
 org=management('organizations/azzhwhfagnawecqfimtq')
 addons=management('projects/'+ref+'/billing/addons')['selected_addons']
 plan=org['plan'];compute=next((a.get('variant',a.get('variant_id')) for a in addons if a.get('type')=='compute_instance'),None)
 if not compute and plan=='free':compute='Nano (Free plan default)'
 key=api_keys(ref)['service_role']
 request=urllib.request.Request(f'https://{ref}.supabase.co/customer/v1/privileged/metrics',headers={'Authorization':'Basic '+base64.b64encode(('service_role:'+key).encode()).decode()})
 metrics={};cpu_total=cpu_idle=None
 try:
  with urllib.request.urlopen(request,timeout=20) as response:text=response.read().decode()
  for line in text.splitlines():
   if line.startswith('#'):continue
   m=re.match(r'([^ {]+)(?:\{([^}]*)\})?\s+([0-9.eE+\-]+)$',line)
   if not m:continue
   name,labels,value=m.groups();value=float(value)
   metrics[name]=metrics.get(name,0)+value
   if name=='node_cpu_seconds_total':
    cpu_total=(cpu_total or 0)+value
    if 'mode="idle"' in labels:cpu_idle=(cpu_idle or 0)+value
 except Exception:pass
 metrics_time=metrics.get('node_time_seconds')
 metrics_stale=metrics_time is None or time.time()-metrics_time>120
 database=query(ref,'select pg_database_size(current_database()) as bytes')[0]['bytes']
 limit=500_000_000 if plan=='free' else None
 memory=(1-metrics['node_memory_MemAvailable_bytes']/metrics['node_memory_MemTotal_bytes']) if metrics.get('node_memory_MemTotal_bytes') and 'node_memory_MemAvailable_bytes' in metrics else None
 if metrics_stale:memory=None
 cpu=None
 if not metrics_stale and previous and cpu_total and previous.get('cpu_total') and cpu_total>previous['cpu_total']:cpu=1-(cpu_idle-previous['cpu_idle'])/(cpu_total-previous['cpu_total'])
 sample={'observed_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'ref':ref,'plan':plan,'compute_size':compute,'database_bytes':database,'database_limit_bytes':limit,'memory_fraction':memory,'cpu_fraction':cpu,'cpu_total':cpu_total,'cpu_idle':cpu_idle,'metrics_observed_at':datetime.datetime.fromtimestamp(metrics_time,datetime.timezone.utc).isoformat() if metrics_time else None,'metrics_status':'Stale or unavailable' if metrics_stale else 'Fresh provider metrics','egress_bytes':None,'egress_limit_bytes':5_000_000_000 if plan=='free' else None,'egress_status':'Provider monthly usage unavailable through this diagnostic; verify organization Usage dashboard.'}
 usage_file=Path('build/capacity-provider-usage.json')
 if usage_file.exists():
  usage=json.loads(usage_file.read_text(encoding='utf-8'))
  if usage.get('organization')=='azzhwhfagnawecqfimtq' and usage.get('plan')==plan and usage.get('billing_start','9999')<=datetime.datetime.now(datetime.timezone.utc).date().isoformat()<usage.get('billing_end','0000'):
   age=time.time()-datetime.datetime.fromisoformat(usage['observed_at']).timestamp()
   sample.update({'egress_bytes':usage['egress_bytes'],'egress_limit_bytes':usage['egress_limit_bytes'],'egress_observed_at':usage['observed_at'],'egress_scope':'organization','egress_status':('Stale dashboard observation' if age>3600 else 'Verified dashboard observation')+' · hourly provider refresh · '+usage['billing_period']})
 sample['maximum_fraction']=max([v for v in [database/limit if limit else None,memory,cpu,sample['egress_bytes']/sample['egress_limit_bytes'] if sample['egress_bytes'] is not None and sample['egress_limit_bytes'] else None] if v is not None],default=0)
 return sample
def snapshot(ref,sample):
 fields=['plan','compute_size','database_limit_bytes','egress_bytes','egress_limit_bytes']
 def literal(value):return 'null' if value is None else str(value) if isinstance(value,(float,int)) else "'"+str(value).replace("'","''")+"'"
 query(ref,'insert into classmate.hosting_usage_snapshots('+','.join(fields)+",source,details) values("+','.join(literal(sample[k]) for k in fields)+",'Authenticated Management API, Metrics API and labelled dashboard observation',"+literal(json.dumps({'compute_source':'Free plan default' if 'default' in str(sample['compute_size']) else 'Selected addon','memory_fraction':sample['memory_fraction'],'cpu_fraction':sample['cpu_fraction'],'egress_status':sample['egress_status'],'metrics_observed_at':sample['metrics_observed_at'],'metrics_status':sample['metrics_status'],'egress_scope':sample.get('egress_scope'),'egress_observed_at':sample.get('egress_observed_at')}))+"::jsonb);")
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--ref',choices=[STAGING,PRODUCTION],default=STAGING);p.add_argument('--watch',action='store_true');p.add_argument('--seconds',type=int,default=1500);a=p.parse_args()
 previous=None;end=time.time()+a.seconds
 while True:
  try:
   sample=collect(a.ref,previous);snapshot(a.ref,sample)
   if a.ref==STAGING:Path('build/capacity-resource.json').write_text(json.dumps(sample),encoding='utf-8')
   with Path('build/capacity-resources.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(sample)+'\n')
   print(json.dumps({k:v for k,v in sample.items() if k not in ['cpu_idle','cpu_total']}),flush=True);previous=sample
  except Exception as e:print('Resource snapshot unavailable:',type(e).__name__,flush=True)
  if not a.watch or time.time()>=end:break
  time.sleep(30)
