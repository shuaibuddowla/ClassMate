"""Inspect release delivery progress; never print device tokens or contact details."""
import sys, pathlib, json
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'scripts/capacity'))
from platform_access import query, PRODUCTION
sql="""
select a.version_code,a.version_name,e.jobs_prepared,e.delivered_at,e.last_error,
  (select jsonb_object_agg(state,n) from (select state,count(*) n from classmate.notification_jobs where event_id=e.id group by state) s) jobs,
  (select jsonb_object_agg(state,n) from (select state,count(*) n from classmate.notification_client_receipts where outbox_id=e.id group by state) s) receipts
from classmate.app_releases a join classmate.notification_outbox e on e.record_id=a.id and e.kind='app_update'
where a.version_code>=28 order by a.version_code;
"""
print(json.dumps(query(PRODUCTION,sql)))
