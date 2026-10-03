# University rollout readiness operations

## Delivery queue

The dispatcher claims at most 100 device jobs at a time and sends with eight parallel workers. It stops claiming before its 70-second budget expires. The scheduler wakes it every minute; HTTP timeout covers the invocation budget. Academic events take precedence over release broadcasts, including events prepared between device chunks.

Each event/device hash has a unique job and a two-minute lease. A crashed worker leaves its lease to expire; chunk progress does not consume retry attempts. Current membership, teacher assignments and silent notice state are checked immediately before sending. Temporary failures use exponential backoff, jitter and FCM Retry-After. Invalid tokens are removed. Exhausted jobs remain visible for explicit owner retry.

Existing accepted delivery receipts are imported when jobs are prepared. Old RPCs and Android payload fields remain available. FCM acknowledgement is not proof of phone display. Client receipt states explain permission/channel/app-toggle blocks. Android display history is persisted per account/environment for 90 days; duplicates can still exist at the provider level.

Resource deletion runs in a separate worker and cron job. Object deletion is idempotent, and failed cleanup remains queued. It cannot hold the notification dispatcher behind R2 requests.

## Owner health

Manage → System health is protected server-side by active owner identity. It shows queue age, pending jobs, accepted sends, failures, client report states, database size, largest tables and hosting snapshots. Queue age over five minutes and database/bandwidth over 80% of verified allowances are highlighted. Missing metrics are not interpreted as zero; stale snapshots and unavailable monthly egress are labelled explicitly.

For a new hosting snapshot, run:

```powershell
python scripts/capacity/observe.py --ref cbskpifgbdbgdmnnxfrh
```

This uses local CLI authentication, not credentials in the Android app. To measure staging during a test, use `--watch --seconds 1800` with the staging default. No billing settings are changed. Check the authenticated organization Usage dashboard for monthly egress when the diagnostic API cannot supply it. Re-run snapshots after changing the plan or compute.

## Read history

Read receipts remain intact. `notice_readers_page` returns at most 50 readers, with a timestamp/UUID cursor; the legacy reader RPC remains available. Feed details return engagement, authors, counts and at most four actual reader previews per notice, bounded to 100 notices. Authorization is checked once per requested notice inside the combined query. Prepared authorization queries retain the same permission predicates as existing RLS checks.

## Tests and release

See `scripts/capacity/README.md` for isolated staging load tests and cleanup. Production receives read-only validation and a small controlled delivery test, never a synthetic mass load. Keep personal credentials, test JWTs and raw tokens in ignored local files only.

Version 1.1.17 includes the owner panel, combined feed query, paginated readers, durable client deduplication and onboarding recreation fix. The release APK must be signed with the existing `key0` certificate. Publish through `scripts/publish-classmate-github-update.ps1` after verifying the signature and version. Do not install through ADB for this release; users test the existing updater.

A capacity result only applies to its measured workload and hosting tier. Review `docs/ROLLOUT_CAPACITY.md` for the measured result and remaining limitations before a university-wide rollout.
