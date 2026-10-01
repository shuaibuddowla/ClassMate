-- Trusted Edge Functions run as service_role and invoke classmate RPCs and
-- trigger helpers while activating file metadata and dispatching notifications.
begin;
grant execute on all functions in schema classmate to service_role;
commit;
