# ClassMate AI and social comments

## Enable Gemini

The web UI and the `classmate-ai` Supabase Edge Function are installed. Production has three server keys configured and a real read-only generation was verified. To configure another environment, add these under **Supabase → project → Edge Functions → Secrets**:

```
GEMINI_API_KEY_1=your first key
GEMINI_API_KEY_2=your second key
GEMINI_API_KEY_3=your third key
GEMINI_MODEL=gemini-3.5-flash-lite
```

One key is sufficient; the other two are optional. Do not put keys in Vercel public environment variables, source files, or chat. Updating Supabase secrets does not require another web build.

The default is the stable **Gemini 3.5 Flash-Lite** model, chosen for fast academic text composition, translation and structured action planning with an available free tier. Availability and quotas are project-specific. Check the [official model](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite), [pricing](https://ai.google.dev/gemini-api/docs/pricing) and [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).

**API keys from the same Google project share limits.** Three keys do not triple the free quota. By default all keys use the `shared` quota group, so a 429 cools down the whole group and respects Retry-After. If keys belong to distinct legitimate projects, set `GEMINI_QUOTA_GROUP_1`, `_2`, `_3` to their actual project identifiers. Keys from one project must use the same group. Temporary failures can use another configured key; project limits are never bypassed.

## Where to use it

- The general AI navigation tab is hidden. Authorized owners/admins and active batch CRs use **Profile → ClassMate AI**, which opens the workspace page. The authenticated `ai_can_write` check controls this shortcut; other profiles cannot open the workspace through its old hash URL. Notice **AI compose** remains available under its existing management permissions. Saved conversations are preserved.
- Mobile and narrow desktop layouts keep all chat-history actions behind the three-line menu. Each tab entry starts a fresh draft; saved conversations remain in history. Only the first visit per account/browser shows up to three role-appropriate suggestions. Desktop Enter sends, Shift+Enter inserts a newline, and IME composition is preserved. Replies align at the start of the submitted prompt rather than jumping to the answer's end.
- Each account has its own saved conversations, scoped to the authorized batch. Use **New chat**, rename/delete controls, **Older chats** (30 per page), and **Load earlier messages** (50 per page). Messages survive reloads and sign-ins. The server supplies the recent conversation context; it does not trust client-supplied assistant history. Only trusted request completion can store assistant replies. Removing owner/batch access hides previously restricted chats.
- **Notices → Post → AI compose** is the default for an authorized owner/CR. Paste rough English/Bengali text, then click **Compose & post**. The explicit submit action authorizes one English notice. Missing facts produce a clarification, never a partial post.
- Specify course and today/tomorrow for cancellation. The existing cancellation RPC creates the notice and class-change record, retaining the red cancelled timetable row and label.
- Begin with `/silent` for feed-only posting, including AI cancellations. No phone/browser notification is enqueued for that new notice.
- Use the small language icon + **Translate** control beside a notice timestamp to translate its title/body into Bengali. **Show original** switches back. Original database content is preserved; translations are cached by notice content hash under reader access checks.

## Roles and writes

Students and teachers can ask questions about academic records their accounts may read. They cannot perform AI writes.
Their suggestions and system instructions are limited to timetable, library, buses, calendar and notices. Write suggestions are shown only after the authenticated server confirms current owner/CR authority, including CR expiry and batch membership.

CRs can compose/edit/delete notices subject to existing notice-management rules and manage their batch's timetable. **Buses are shared university-wide**, using the original `bus_schedules` table and existing owner/active-CR permission. Bus plans clearly state their global effect. There is no batch-specific bus database or override.

Owners can use the current management RPCs for notices, schedules, buses, calendars, courses, teachers, CR assignments, student approvals, departments, batches, semesters, file metadata/deletion, notification retry, and owner health inspection. File upload still uses the real upload UI; AI cannot fabricate file contents. Account security, arbitrary SQL, billing, and credential changes are not tools.

General agent writes are displayed as a proposed plan and require **Apply changes**. The server stores the plan, rechecks the signed-in user and current permissions, then applies the fixed typed RPCs in one transaction. Client edits cannot alter the stored plan. A failed action rolls back the whole plan. Request IDs and row locks prevent repeated network submissions from duplicating writes. Operations needing an ID from a newly created record are split into follow-up steps.

Owner-only AI results are tagged by the server. Removing owner access also blocks reading or replaying those cached results. Failed retries count toward the account rate limit; project cooldowns cannot be shortened by a concurrent retry.

File deletion queues the existing R2 cleanup workflow; physical deletion remains asynchronous. Existing Android payloads and RPCs are retained.

## Data and availability

Gemini receives bounded academic records: course names/IDs, times, calendar entries, accessible notice text and file metadata. It does not receive database credentials, push tokens, signed file links, private file contents, or Friends contact details. Owner people lookups include identity fields needed for administration, not personal contact profiles. Each read is scoped through authenticated server functions. Requests are online-only, limited per account, and have a bounded invocation deadline.

Academic questions are limited to retrieved records; the assistant asks for clarification when needed. Suggestions remain model-generated and should be reviewed before applying schedule or destructive changes.

## Social comments

The web Comments sheet now includes real names/avatars, threaded replies, likes, editing one's own comments, authorized deletion, keyset pagination (50 at a time), safe retry IDs and periodic refresh while open. New RPCs enforce notice access and parent-notice consistency. Deletion is soft and hides associated replies. Older APK APIs and comment data are preserved.

## Verification

### Native Android (1.1.22)

Android uses the same authenticated Edge function and RPCs as the web client. No Gemini keys or database service credentials are added to the APK.

- Authorized admins/CRs get AI compose as the default notice-posting mode. Rough English/Bengali text is composed and posted in English; missing cancellation details prompt clarification. Retry IDs survive rotation. `/silent` remains a feed-only server rule.
- Profile exposes the native AI workspace only after `ai_can_write` succeeds. Chats, rename/delete and paginated messages reuse server history. Agent changes need review and Apply; the server rechecks permissions and executes the stored plan. CR timetable writes stay batch-scoped; buses remain university-wide. Returning after changes refreshes academic content.
- Notice Translate/Original uses real notice IDs and the server translation cache. Local translations expire when the source text changes.
- Comments use 50-item pages, real author profiles, replies, likes, authorized edits/deletion and idempotent posting. Failed writes preserve the draft.
- The push switch reflects Android permission and channel state. Enabling requests permission or opens notification settings when blocked; token registration failure offers retry. Disabling unregisters this Android device only.
- About Developer reads the public developer profile RPC and links to the approved Facebook, GitHub and portfolio addresses.

Native verification: production-configured debug build and local unit tests. A physical-device end-to-end test of these new native screens is still needed before publication. Build signing uses the existing production keystore; never replace it with a new key.

- Strict web TypeScript and Deno Edge type checks.
- Unit checks for role tool restrictions, typed plans, silent intent, same-project cooldown, Retry-After and temporary provider failover.
- Staging authenticated checks for comments/replies/likes, author-only edits, cross-batch denial, revoked CRs, atomic rollback, idempotency, shared buses and silent cancellation without alerts. Synthetic writes roll back.
- Browser checks for mobile comments in both themes, composition defaults, Bengali toggle and shared-bus plan review.

Real production Gemini generation and persistence of both chat messages were verified with one temporary owner conversation, which was removed afterward. No database action plan was applied. Staging also verified failed-request persistence/retry deduplication with unavailable provider keys, per-account RLS, revoked-owner history denial, 50-message pagination, and context catalogs containing zero or multiple courses. Five deployed-browser checks passed for workspace navigation, history reload/rename/delete, student suggestions, composition, comments, shared-bus plan review and installation controls. Chrome reports zero installability errors on production. Schedule writes and translation were validated with mocks/authorization tests; real model-driven mutations were not executed in production.

Deployment helpers: `scripts/web/deploy-ai.py staging|production`, the standard Supabase function deployment CLI, and the existing Vercel workflow. No billing changes or Android installation are required.
