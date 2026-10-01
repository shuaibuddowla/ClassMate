-- The service key is confined to trusted Edge Functions. A custom schema does
-- not inherit the public schema's service_role grants.
begin;
grant usage on schema classmate to service_role;
grant all on all tables in schema classmate to service_role;
grant usage, select on all sequences in schema classmate to service_role;
commit;
