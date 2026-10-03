# ClassMate rollout capacity report

Measured 3 October 2026 (Asia/Dhaka). API run: 2026-10-02T22:51:35.544Z to 2026-10-02T23:13:16.183Z UTC.

## Recommendation

**Start with 250 concurrently active users on the current tier.** This is the largest tested level where every exercised API operation met p95 <1s, p99 <3s and errors <1%. There were no errors at 25, 100 or 250 users.

500 active users sustained for 15 minutes passed the aggregate thresholds, with p95 **501 ms**, p99 **1.19s**, and **0.20%** unexpected errors. However, feed-details p95 was **1.010s** and timetable-details transport errors were **1.40%** for that operation. The synchronized first-load burst was also slow. Treat 500 as a measured aggregate result, not an unconditional production capacity promise.

**The current Free/Nano tier is not cleared for the complete 5,000-device release-broadcast target.** That test was stopped at the required 85% resource guard when CPU reached 96.27%. 500-device academic alerts and 1,000-device notices passed. No subscription or billing changes were made.

## Verified hosting and usage

Both production and staging belong to the authenticated Free organization. Management API selected compute addons were empty; compute is therefore labelled **Nano (Free-plan default)** rather than a selected paid addon. Provider metrics reported approximately 455 MB total machine memory, consistent with the Free allocation.

Current verified allowances: 500 MB database per project, 5 GB uncached organization egress, 5 GB cached egress and 50,000 MAU ([Supabase pricing](https://supabase.com/pricing)). The authenticated Usage dashboard for 30 Sep–30 Oct showed **0.048 GB uncached egress**, **0 GB cached egress**, **11 MAU** and **7,152 Edge invocations**. Observed 23:05:45 UTC on 2 October; provider usage refresh may lag by one hour and does not include all later test traffic immediately.

The health panel records the source, observation time and organization scope. The diagnostic API cannot retrieve monthly egress directly; dashboard observations are explicit, become stale after an hour and are ignored after a plan or billing-cycle change. Missing/stale provider metrics are not treated as zero.

Production database after verification: **103,058,579 bytes** (~98.3 MiB). Staging during the API run peaked at **122,080,403 bytes** (~116.4 MiB); peak sampled memory was **78.9%**, CPU **48.8%**. No API-run resource stop triggered. CPU is conservatively measured as one minus idle time, including other non-idle categories on shared compute.

## API workload and results

Fixtures: 5,000 active synthetic student accounts, four batches, 200 silent notices and 250,000 retained read receipts. First 500 virtual users covered the 100-user batch and 400 students in the 500-user batch. Ordinary authenticated JWTs exercised PostgREST, RLS and server authorization, not a service-role bypass.

Seven operations repeated with ten-second think time: timetable reads, 50-notice loading plus combined feed details, ten-receipt writes, likes, Friends search, 50-reader pagination and timetable details. There was one routine slot per fixture batch, short notice text and no attachment downloads. Google OAuth/sign-in throughput, teacher-heavy concurrent usage, Realtime sockets, long resource downloads and idle-project resume were not load tested.

| Active users | Duration | Warm requests | p95 | p99 | Errors | Aggregate | Every operation |
|---:|---:|---:|---:|---:|---:|---|---|
| 25 | 130s | 344 | 186 ms | 207 ms | 0.00% | Pass | Pass |
| 100 | 130s | 1,369 | 209 ms | 258 ms | 0.00% | Pass | Pass |
| 250 | 130s | 3,397 | 213 ms | 344 ms | 0.00% | Pass | Pass |
| 500 | 910s | 50,222 | 501 ms | 1193 ms | 0.20% | Pass | Not all pass |

The 500-user run processed ~55 warm requests/s. There were 101 transport-level failures with no HTTP response: 17 routine reads and 84 timetable-detail calls. The original harness did not capture their underlying socket error, so a server cause is not established. Future runs record error names/codes. Notice reads, feed details, receipt writes, likes, Friends and reader pagination had no errors in this run.

First-request synchronized bursts (separate from warm usage):

| Users | p95 | p99 | Errors |
|---:|---:|---:|---:|
| 25 | 1092 ms | 1094 ms | 0 |
| 100 | 1238 ms | 1248 ms | 0 |
| 250 | 2654 ms | 2682 ms | 0 |
| 500 | 5201 ms | 5332 ms | 0 |

These are first client-request bursts against an already seeded database, not a measured cold resume from a paused project. Cached screens improve repeat visits; they do not make these first network loads instant.

### Query improvements

The original representative 100-user test failed: aggregate p95 7.45s and p99 13.92s. An earlier one-notice test was not representative and was excluded from the final recommendation.

Repeated SQL authorization planning was the main feed bottleneck. Prepared PL/pgSQL authorization queries preserve the existing predicates; combined feed details check each requested notice once and limit requests to 100 IDs. The same 50-notice database EXPLAIN measured approximately **530 ms before / 37 ms after**, followed by the full ramp above. Reader previews use an index-backed four-row lateral limit. Friends prefilters the allowed batch before membership checks.

## Notification delivery

Per-device durable jobs, unique event/token identities, two-minute leases, eight parallel sends, claims of 100 and a 70-second invocation budget are deployed. Chunk progress does not count as failure. Temporary failures back off with jitter and FCM Retry-After; invalid tokens are removed, exhausted jobs remain available for explicit owner retry. Academic events precede release broadcasts, including new events prepared between chunks. R2 cleanup has its own worker/scheduler.

Staging-only healthy mock with 100 ms provider latency:

| Audience | Accepted | Drain time | Result |
|---:|---:|---:|---|
| 100 | 100 | 9.91s | Pass |
| 500 | 500 | 25.53s | Pass |
| 1,000 | 1,000 | 47.97s | Pass |
| 5,000 release devices | Incomplete | Stopped at resource guard | Not verified |

The first notification run stopped at 91% CPU. Per-device permission plans and receipt eligibility were then optimized, eliminating a full-audience scan for each client receipt. The rerun passed 1,000 devices but the 5,000-device release still exceeded the guard at 96.27% CPU. A manual observation before cleanup saw 1,912 accepted device jobs; this is not a final accepted count or proof of full drainage. Automatic wakeups, scheduled workers and the controlled benchmark runner can overlap; lease tests prevent two workers from claiming the same active job. No real phones received mass test pushes.

Rollback SQL regressions cover concurrent claims, expired leases/worker crash recovery, stale acknowledgements, revoked teacher eligibility, invalid token removal, retry delay, retained failures/owner retry, academic priority, batch isolation and `/silent`. Policy tests cover FCM failure classifications, exponential backoff, jitter and numeric/date/long Retry-After. Current eligibility is rechecked before sending; accepted delivery is not exactly-once provider delivery. The updated Android client persists display deduplication per account/environment.

## Production validation and cleanup

Migrations 011–018 and both workers are deployed in production; migration history is repaired. Read-only validation confirmed owner diagnostics succeed, student/anonymous access is denied, both cron jobs are active and no production events are pending.

A controlled **one-device real FCM acceptance** check passed. It used a temporary isolated batch and recipient, restored the owner's token in `finally`, and removed its fixtures. **Phone display was not verified**: the signed-in owner client intentionally rejects the disposable recipient identity. Real display remains separate from provider acceptance.

All tagged staging load accounts, notices, events and read receipts were removed; real staging data remained. Private fixture JWTs were deleted. Mock mode was restored to false. Production still has 10 real profiles and 60 read receipts; owner device ownership was restored and temporary production fixtures are absent. Cleanup only targeted explicit test identifiers.

## Database growth and monthly bandwidth

The staging notice_reads table and its indexes occupied **94,330,880 bytes** for 250,000 live receipts (~377 allocated bytes/receipt). This includes allocation/bloat from repeated seeding, not a measured compact steady-state row cost. Staging allocated database size remained ~123.6 MB after cleanup; deleting rows does not immediately shrink PostgreSQL files. Existing production read history was not deleted.

Conservative allocated growth scenarios: 5,000 users reading ten new notices each per month adds 50,000 receipts (~19 MB/month at that measured allocation). Twenty new reads per user per day adds about three million receipts/month (~1.13 GB/month). These are workload scenarios, not forecasts of actual student activity; monitor table growth and indexes before reaching 80% of the allowance. No automatic history retention/deletion is enabled.

The 500-user sustained run returned **605,136,917 decoded payload bytes**. At that workload, 5,000 daily users active for 15 minutes on 30 days project approximately **179.5 GB/month decoded**; 10% daily activity projects ~18.0 GB, 1% ~1.80 GB. These are **not billable egress estimates**: compression, actual usage, client caching, long notices, OAuth, FCM and R2 downloads were not fully measured. R2/private-file and GitHub APK traffic are separate from these API byte counts. Compare real provider egress after a pilot, not only payload arithmetic.

## Proposed upgrade and release

For a university-wide pilot with more headroom, evaluate **Pro + Micro** and repeat these tests. Published pricing: $25/month organization plan, $10/month Micro compute per project, with $10/month compute credit. Keeping both production and staging active on Micro is approximately **$35/month before overages/taxes**. Production Small plus staging Micro is approximately **$40/month**. These estimates use [Supabase pricing](https://supabase.com/pricing), not a guaranteed capacity fix; rerun the failed broadcast and first-load tests on the chosen tier. No upgrade was purchased.

Android unit tests and production debug/release assemblies passed. Source version is **1.1.17 (18)**, including the duplicate-onboarding fix, owner health panel, paginated readers, combined feed query and persistent push deduplication. The release assembly is unsigned; the existing `key0` signing step and GitHub publishing workflow are still required. No ADB installation was performed.

## Sources and evidence

- [Supabase pricing](https://supabase.com/pricing)
- [Compute and disk](https://supabase.com/docs/guides/platform/compute-and-disk)
- [Metrics](https://supabase.com/docs/guides/observability/metrics)
- [FCM scaling and retry guidance](https://firebase.google.com/docs/cloud-messaging/scale-fcm)
- [Operations](ROLLOUT_READINESS.md), [test runner guide](../scripts/capacity/README.md)

Raw local evidence is retained in ignored build/: capacity-load-results.json, capacity-resources.jsonl, capacity-notification-results.json, the stopped benchmark logs, capacity-provider-usage.json, capacity-table-growth.json, capacity-production-validation.json, capacity-production-delivery.json and capacity-cleanup.json. Private test tokens are not retained.
