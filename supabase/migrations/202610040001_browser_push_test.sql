begin;
alter table classmate.device_tokens add column last_web_test_at timestamptz;
create function classmate.claim_browser_push_test(target_token text) returns boolean
language plpgsql security definer set search_path='' as $$
begin
 if not classmate.is_active() then raise exception 'Active account required' using errcode='42501'; end if;
 update classmate.device_tokens set last_web_test_at=now() where profile_id=auth.uid()
 and fcm_token=target_token and client_platform='web'
 and (last_web_test_at is null or last_web_test_at<now()-interval '1 minute');
 return found;
end $$;
revoke all on function classmate.claim_browser_push_test(text) from public,anon;
grant execute on function classmate.claim_browser_push_test(text) to authenticated;
notify pgrst,'reload schema';
commit;
