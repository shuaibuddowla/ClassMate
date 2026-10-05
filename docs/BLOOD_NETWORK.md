# Emergency blood donor network

Entry point: **Friends → Blood requests**, on Android and web. This is emergency coordination, not clinical advice or an eligibility certificate.

## Workflow

- An active verified account submits a hospital/location, blood group, requested units (1–20), attendant phone and deadline within seven days. The request can reach the university or its originating batch.
- The request remains pending until the owner or an active, unexpired CR of the originating batch verifies the details. Students cannot approve requests; other batch CRs cannot approve them. Verification means confirming the request's logistics, not medical compatibility.
- On approval, the existing Supabase durable delivery queue sends alerts only to matching, opted-in verified members. Default recruitment is the exact blood group; the organizer may select compatible **red-cell** donors if the hospital accepts them. Never apply the red-cell matrix to plasma or platelets.
- Eligibility is checked again before each leased delivery and donor response. Opt-out, batch change, inactive membership, donation cooldown, request closure or deadline expiry suppresses sending.
- Donor settings are opt-in, separate from normal Push notifications. The donor must also allow notifications and enable Push notifications in Profile. Missing/unknown blood group or missing mobile prevents enrolling.
- Donors can volunteer, withdraw, call the attendant and record donation dates. Organizers/verifiers can contact volunteers, mark fulfilled or cancel. A fulfilled request does not automatically claim someone donated.
- Volunteering discloses the donor's name/mobile only to the organizer and authorized verifier. There is no public university donor directory. Contact details and donation records are not written into offline caches, diagnostics or push payloads.

## Donation interval and safety

Recorded donations suppress matching alerts for **120 days**. History is append-only through the user API: entering an earlier date cannot erase the latest donation. A clinician/blood bank must determine actual eligibility, typing and cross-match; the app does not infer eligibility from dates alone. It does not collect patient names or clinical histories.

Sources: [WHO donor selection guidance](https://iris.who.int/bitstream/handle/10665/76724/9789241548519_eng.pdf) describes whole-blood intervals of 12 weeks for males and 16 weeks for females. The app uses a conservative single 120-day recruitment filter rather than making clinical decisions. [American Red Cross blood types](https://prod-www.redcrossblood.org/donate-blood/blood-types.html) explains O-negative universal **red-cell** donation and the need for typing/cross-matching.

## Backend and push

Migration `202610050001_blood_network.sql`: `blood_requests`, `blood_donor_preferences`, `blood_donations`, `blood_responses`. RLS denies direct anonymous/authenticated table access; permission-checked RPCs serve a 20-request feed and bounded volunteer details (50).

Request creation/volunteering are idempotent. Per-account request rate is limited to three per 24 hours, with an advisory lock preventing concurrent bypass. Approval is serialized and queues one event only. Existing notices, `/silent`, academic batch isolation, Android payloads and browser-origin checks remain intact.

The `blood_request` kind extends the current queue/context, never a second sender. Notifications retain eight send workers, leases/retries and persistent client deduplication. Emergency alerts share academic priority above release broadcasts. Payload TTL is capped to the remaining deadline; browser display rejects missing/expired blood deadlines. Already displayed alerts cannot be recalled, so opening the request always checks current server state.

New Android clients revalidate eligibility before display and use `classmate_blood_emergency`, a high-importance channel with the bundled short chime. The “I can donate” action records an authenticated response after another eligibility check, then opens the request; “Call attendant” opens the phone dialer. Browser notifications use supported action buttons to open the authorized request page; the user confirms volunteering/calling there. Browser/OS controls determine sound, permission, action-button support and delivery timing; no DND bypass or guaranteed alert is claimed. Older APKs retain their generic notification path; upgrade to 1.1.24 for the dedicated experience.

## Verification/deployment

`scripts/web/deploy-blood.py staging|production` applies/records the migration using existing local Management API credentials. `scripts/web/verify-blood.py` runs isolated fixtures and rolls back in staging; real device alerts are never sent by those tests. Deploy the revised `dispatch-classmate-notifications` Edge Function to each environment, and deploy the web worker/UI to the matching Vercel project.

Android source is 1.1.24 (25). Build/sign with the existing key0 before publishing; never install a debug build over a signed baseline. The older `release/app-release.apk` remains the previously signed 1.1.23 artifact until a new signed build is produced.

### Verified 5 October 2026

- Migration and revised dispatcher deployed to staging, then production; web deployed to both Vercel environments.
- Staging rollback checks passed: all 64 blood-group combinations, exact-only matching, student/cross-batch approval restrictions, unexpired CR checks, consent, donation-history privacy and 119/120-day boundaries, inactive membership, expired deadlines, fulfilled requests, idempotent volunteering, request-rate limit, leased-job context and revoked consent. No fixture rows remain.
- Web: type checks, 25 unit tests, production builds and browser request/settings/volunteering/responsive checks passed (320, 390, 1440 px). Mobile screenshot reviewed.
- Android: debug build and existing 37 unit tests pass. This is source verification, not a signed 1.1.24 release or a physical-device blood-alert sound test.
- Production validation was read-only: zero blood requests, donor preferences or queued blood alerts immediately after deployment. No synthetic alerts were sent to real users. Actual device sound/action behavior still needs a controlled test after the new signed APK is installed.
