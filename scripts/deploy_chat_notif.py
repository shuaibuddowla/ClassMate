import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql = """
CREATE OR REPLACE FUNCTION classmate.notification_job_context(target_event uuid, target_hash text, target_lease uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  j classmate.notification_jobs%rowtype;
  r classmate.blood_requests%rowtype;
  t classmate.batch_fund_transactions%rowtype;
  m_rec classmate.chat_messages%rowtype;
  e classmate.notification_outbox%rowtype;
  matched boolean;
  own_group text;
  amt_str text;
begin
  select * into e from classmate.notification_outbox where id = target_event;
  if not found then return null; end if;

  -- Chat message push notification payload
  if e.kind = 'chat_message' then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select * into m_rec from classmate.chat_messages where id = e.record_id;
    if not found or m_rec.is_deleted or m_rec.is_unsent then return null; end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', e.batch_id,
      'recipient_id', j.profile_id,
      'kind', 'chat_message',
      'record_id', m_rec.id::text,
      'room_id', m_rec.room_id,
      'room_type', (case when m_rec.room_id like 'batch_%' then 'group' else 'dm' end),
      'sender_id', m_rec.sender_id::text,
      'sender_name', m_rec.sender_name,
      'sender_avatar', coalesce(m_rec.sender_avatar_url, ''),
      'title', case when m_rec.room_id like 'batch_%' then 'Discussion Group: ' || m_rec.sender_name else m_rec.sender_name end,
      'body', coalesce(m_rec.content, case when m_rec.image_url is not null then 'ðŸ“· Photo' else 'Message' end),
      'timestamp', (extract(epoch from m_rec.created_at) * 1000)::bigint,
      'failures', j.failures
    );
  end if;

  if e.kind in ('batch_fund_deposit', 'batch_fund_update') then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select * into t from classmate.batch_fund_transactions where id = e.record_id;
    if not found then return null; end if;

    if t.student_profile_id is distinct from j.profile_id then
      return null;
    end if;
    if e.recipient_profile_id is not null and e.recipient_profile_id <> j.profile_id then
      return null;
    end if;

    if t.amount = trunc(t.amount) then
      amt_str := trim(to_char(t.amount, 'FM999,999,990'));
    else
      amt_str := trim(to_char(t.amount, 'FM999,999,990.00'));
    end if;

    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', e.batch_id,
      'recipient_id', j.profile_id,
      'kind', e.kind,
      'record_id', t.id::text,
      'title', case when e.kind = 'batch_fund_update' then 'Deposit Updated: â‚³' || amt_str else 'Deposit Confirmed: â‚³' || amt_str end,
      'body', case when e.kind = 'batch_fund_update'
        then 'Your deposit of â‚³' || amt_str || ' for "' || left(t.title, 40) || '" has been updated.'
        else 'Your deposit of â‚³' || amt_str || ' for "' || left(t.title, 40) || '" has been recorded.' end,
      'failures', j.failures
    );
  end if;

  if e.kind = 'blood_request' then
    select * into j from classmate.notification_jobs
    where event_id = target_event and token_hash = target_hash and lease_id = target_lease
      and state = 'leased' and lease_until > now();
    if not found or not classmate.notification_device_allowed(target_event, j.profile_id, target_hash) then
      return null;
    end if;

    select r0.* into r from classmate.blood_requests r0 where r0.id = e.record_id;
    if not found then return null; end if;

    matched := classmate.blood_matching_alert(r.id, j.profile_id);
    select blood_group into own_group from classmate.profiles where id = j.profile_id;
    return jsonb_build_object(
      'fcm_token', (select fcm_token from classmate.device_tokens where token_hash = target_hash and profile_id = j.profile_id),
      'batch_id', (select batch_id from classmate.profiles where id = j.profile_id),
      'recipient_id', j.profile_id,
      'kind', e.kind,
      'record_id', r.id::text,
      'title', case when matched then 'Urgent: ' else '' end || r.blood_group || ' blood needed',
      'body', case when matched then 'Your profile lists ' || own_group || '. Can you help? Contact the attendant at ' || r.hospital || '.'
        else 'Know a friend or someone who might help? ' || r.units || ' unit(s) needed at ' || r.hospital || '. Open for contact details.' end,
      'blood_match', matched,
      'expires_at', r.needed_by,
      'failures', j.failures
    );
  end if;

  return classmate.academic_notification_job_context(target_event, target_hash, target_lease);
end $function$;
"""

def main():
    print("Updating notification_job_context with chat_message support...")
    res = query(PRODUCTION, sql)
    print("Result:", res)

if __name__ == '__main__':
    main()
