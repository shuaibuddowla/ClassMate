# Staging capacity checks

These scripts are restricted to `qpryxvunhebwljlwmggk`. They use the locally authenticated Supabase CLI account. Credentials, device tokens and personal contact data are never included in result logs. Private synthetic JWTs stay in ignored `build/` and are removed by cleanup.

## API load test

1. Apply the rollout migrations and run `supabase/tests/classmate_rollout_readiness.sql` in staging.
2. Run `python scripts/capacity/fixtures.py seed`. This removes only the fixed `d5202610-` fixtures, then creates 5,000 synthetic accounts, four batches, 200 silent notices and 250,000 read receipts.
3. In a separate terminal run `python scripts/capacity/observe.py --watch --seconds 1800`.
4. Run `node scripts/capacity/load.mjs`. Ramps: 25, 100, 250 for two minutes each, then 500 for 15 minutes, with ten-second think time. `--smoke` is a 25-user diagnostic only.
5. Inspect ignored `build/capacity-load-results.json` and `build/capacity-resources.jsonl`. Cold requests are separate. Response byte counts are decoded API payloads, not measured billable bandwidth.

The test authenticates ordinary student JWTs through PostgREST and uses real RLS/RPC checks. It does not measure Google sign-in or token refresh throughput. Feed requests include 50 notices and their details. Errors above 5% over a minute or verified resource usage at 85% stop the test.

## Notification benchmark

Run `python scripts/capacity/notification_benchmark.py` after the API test finishes. It enables a staging-only mock, exercises 100/500/1,000 academic devices and 5,000 release devices, and restores mock mode in `finally`. Real staged devices are skipped during mock mode. Never interrupt the process without checking the mock flag and tagged outbox records. A 500-device academic notice must finish in two minutes; a 5,000-device release must finish in 20 minutes. Acceptance is not phone display.

## Cleanup

Run `python scripts/capacity/fixtures.py cleanup`. This deletes only tagged synthetic fixtures and the private JWT file. Real staging accounts and all production data are preserved. SQL regression tests use a separate `e2000000-` prefix and always roll back.

## Hosting snapshots

`python scripts/capacity/observe.py --ref cbskpifgbdbgdmnnxfrh` records a production snapshot without running a load test. Plan is authenticated from Management API; compute is the selected addon or explicitly labelled Free-plan default. Database size and machine metrics are measured. Monthly egress is unavailable through this Management API diagnostic. A timestamped authenticated dashboard observation can be supplied in ignored `build/capacity-provider-usage.json`; it must specify the organization, plan, billing_start/end, observed_at, egress_bytes/limit and billing_period. Usage is organization-wide, may lag by an hour, is labelled stale after an hour and is ignored after its billing cycle or plan changes. Missing values are not zero.

Owner diagnostics are available under Manage → System health. Raw device tokens and service credentials are service-only. Failed jobs require an explicit owner retry. Accepted jobs remain recorded; each device has a lease and event identity. Network acknowledgements cannot guarantee exactly-once FCM delivery, so the Android client also stores display history.
