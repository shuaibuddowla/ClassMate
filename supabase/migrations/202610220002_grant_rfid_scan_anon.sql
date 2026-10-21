-- Migration: Allow anonymous ESP32 scanner clients to access RFID heartbeat and scan processor RPCs
begin;

-- 1. Grant USAGE on schema classmate to anon so PostgREST can resolve functions
grant usage on schema classmate to anon;

-- 2. Grant EXECUTE on process_rfid_scan and get_device_active_session to anon
grant execute on function classmate.process_rfid_scan(text, text) to anon, authenticated, service_role;
grant execute on function classmate.get_device_active_session(text) to anon, authenticated, service_role;

commit;
