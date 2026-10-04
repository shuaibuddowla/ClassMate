# ClassMate Web

Production: https://classmatebd.vercel.app

Browser push registrations now record the request Origin. Production accepts
`https://classmatebd.vercel.app` only; staging accepts its staging address and
localhost. Legacy registrations without a verified origin are retained but
excluded at send time. Opening the current site while signed in automatically
re-registers a browser when notification permission is granted and the in-app
switch is enabled. Otherwise enable Push notifications in Profile on the current
site. Permissions and installed PWAs cannot be transferred across origins.
Migration: `202610040010_web_push_origins.sql`; transactional staging checks:
`scripts/web/verify-push-origins.py`. Android registrations are unaffected.

The browser notification preference is cached per account, separately from its
FCM token. Permission approval immediately saves the preference and closes the
onboarding prompt; token registration continues afterward. A temporary failure
shows a connection/retry state, not a disabled switch. Returning to the app or
coming online retries registration without asking for permission again. Browser
permission remains authoritative. Sign-out removes this browser's registration
without turning off the saved preference or deleting Android device tokens.
Notice insertion uses the shared database outbox regardless of posting client;
batch/teacher eligibility and `/silent` suppression still apply.

Staging: https://classmate-web-staging.vercel.app

## Application

`web/` is the responsive Next.js application. It uses ClassMate's existing Supabase `classmate` schema, Google accounts, profile state, RLS and management RPCs. Firebase is used only for browser push. Academic files remain in private R2 buckets and are accessed through authenticated Edge Functions. On desktop-width screens files open in a new tab; on mobile they open in the current tab. Blocked desktop pop-ups report an actionable error, and failed signing closes the temporary tab.

Mobile uses five navigation destinations. Desktop layouts above 1000px use four sidebar destinations and a persistent, independently scrolling Notices panel on the right. Both panels have draggable inner-edge resize handles, starting at 228px for navigation and 380px for Notices when space permits. Width preferences are saved in the browser; available space limits prevent squeezing the main content. Focus a handle and use arrow keys to resize, or double-click to restore its default width. The calendar and its event list stay centered between the panels. The central academic workspace uses compact rows and controls; batch switching and appearance controls live in Profile; no top toolbar occupies the academic workspace. Narrower tablet layouts retain the Notices destination. Bus schedules and Calendar are inside Timetable. Manage is accessed through Profile and is loaded separately. Visited screens preserve their state until the account or batch changes.

Notices fetch ten entries at a time, with shimmer while loading older entries, real reader previews, 50-reader pagination, optimistic likes, comments, reminders, search highlighting, expandable content and resource links. Search currently searches the loaded notice pages. Library searches the server and paginates resources. Existing server authorization remains authoritative.

## Mobile layout

On phones, batch switching and appearance controls live in Profile, leaving academic screens without a separate top toolbar. Desktop uses a compact floating sidebar and also keeps batch/theme controls in Profile. Theme buttons switch directly between light and dark; Profile also offers device-following mode. Timetable rows show both assigned teacher and room when available. Library filters remain swipeable without visible scrollbars. Profile follows Android's compact account, preferences, Manage and app-update sections; the Android download card opens the latest GitHub release.

## Local configuration

Copy `web/.env.example` to ignored `web/.env.local` and supply public Supabase URL/anon key, Firebase web app configuration and public VAPID key. Never put a service-role key, Firebase service-account JSON or R2 credentials in web variables.

The local helper `scripts/web/configure.py staging` or `production` obtains public configuration using the already authenticated Supabase CLI account. It reads Firebase public configuration and VAPID from ignored `build/` files. These helper files are local conveniences, not checked-in credentials.

From `web/`:

```powershell
npm ci
npm run dev
npm run typecheck
npm test
npm run build
```

The scripts invoke Node directly because Windows npm executable shims can fail when the workspace path contains `&`.

## Authentication and onboarding

Google OAuth uses PKCE. The callback is `/auth/callback`; each exact HTTPS callback is registered in its matching Supabase project. The university email requirement and role/batch determination are enforced by the existing server hook/profile RPC.

New profiles receive account discovery, profile completion and notification prompts. Completed profiles skip profile onboarding. Returning sign-ins offer notification permission when not granted. Browser permission is requested only through a user action. The onboarding prompt closes immediately after permission is granted; token registration proceeds independently and failures appear as dismissible feedback. Contact details are not stored in offline caches.

## Deployment

Run Vercel commands from **`web/`**, not the Android repository root. Use the appropriate project before setting environment variables or publishing:

```powershell
vercel link --yes --project classmate-web-staging
# Or: vercel link --yes --project classmate-web
```

The linked project settings use the Next.js framework with automatic build/output detection. `scripts/web/deploy.py env staging|production` writes only `NEXT_PUBLIC_*` variables to the linked project's production environment. Verify the project link first: the script does not infer it from its environment argument.

```powershell
vercel deploy --prod --yes
```

GitHub automatic deployment is not connected: Vercel reported that its account lacks a GitHub Login Connection. CLI deployment works. Connecting GitHub later is optional; set the repository root directory to `web` for Git deployments.

Server compatibility migrations:

- `202610030005_web_clients.sql`: platform metadata, authenticated web token registration and service-only delivery context wrapper.
- `202610040001_browser_push_test.sql`: caller-owned, rate-limited browser test notification.
- `202610040002_cr_course_posting.sql`: fixes the existing inconsistency that denied active CRs course-scoped posting/deletion in their own active batch. Teacher assignment checks and ordinary-student restrictions remain intact.

Apply staging first, then production. The local `deploy.py migration` helper records applied versions. Deploy `signed-classmate-resource`, `upload-classmate-resource`, `dispatch-classmate-notifications` and `test-classmate-web-push`. Resource/test functions disable the gateway JWT check to support browser preflight; each function independently validates the caller with Supabase Auth. Dispatcher authorization is unchanged.

Set server `CLASSMATE_WEB_ORIGINS` to the exact approved origin. Production allows `https://classmatebd.vercel.app`; staging additionally allows `http://localhost:3000`. R2 CORS configurations are in `scripts/web/r2-cors.*.json`; inspect and merge existing rules before replacing a policy. Buckets stay private. Existing Android OAuth callbacks and payloads are preserved. Google Cloud must also authorize both Supabase callback URIs: `https://qpryxvunhebwljlwmggk.supabase.co/auth/v1/callback` and `https://cbskpifgbdbgdmnnxfrh.supabase.co/auth/v1/callback` on the existing Web OAuth client. A Google `redirect_uri_mismatch` is fixed there, not by adding the Vercel URL to Googleâ€™s redirect list.

## Offline and notification behavior

The service worker precaches the public application shell and its required Next.js scripts/styles together using a cache version per build, so reopening works without a network after an initial online visit. Last-synced routines, date-specific timetable details, buses, calendar, courses and loaded notices are stored under account/batch-specific keys. Offline mode shows saved content and sync time. Saved notice pages can paginate offline; data remains scoped to the same account and batch. Sign-out clears academic and identity caches and removes this browser's push registration. Friends contact details, Auth HTTP responses and signed file links are not cached. Mutations require an internet connection.

Browser push uses data-only FCM messages and a single service-worker display path. Persistent event IDs suppress repeat displays. Events for another account/project are rejected, and notification links resolve records through existing RLS before opening them. `/silent` remains feed-only. Android's data payload is unchanged.

Use Profile â†’ Enable notifications â†’ Send a test notification to check the current browser. FCM acceptance is distinct from visible browser delivery. Browser/OS permissions and platform support can block display. Unsupported browsers get an explanation instead of a broken permission flow. On iOS, web push may require installing the PWA to the Home Screen.

Notice reminders persist through the existing reaction record and become visible when due while the web application is open. They are **not guaranteed background scheduled alerts**; a server reminder scheduler is not part of this release.

## Verification

Verified in isolated staging fixtures:

- Google sign-in and main-screen loading confirmed by the user.
- Seven unit tests: month alignment across 2024â€“2032, leap years, four/five/six rows, Thursday/Friday closures, class-only holidays, working-day overrides and unchanged Android/data-only web push payloads.
- Seven browser checks: narrow-screen layout, ten-item notice pagination, read receipts, Calendar/Bus/Library/Friends, CR forms, offline reload, logout cache clearing, browser R2 upload, metadata editing and permanent deletion.
- Worker check: duplicate suppression across reload and rejecting another account's event; the display API is mocked in this automated check.
- Seventeen authenticated API/CORS checks, including student/CR/teacher denial of owner diagnostics and cross-batch access denial.
- Additional staging RPC checks: silent notice create/edit/delete, receipt writes, CR course creation/removal and timetable slot creation/deletion.
- Real owner browser test accepted by FCM and confirmed by the user to appear once.

Synthetic fixtures are restricted to staging and tagged `ae042026-`. Their private test sessions/reports stay in ignored `build/`. Never publish Playwright traces containing test authentication headers. Cleanup only the tagged fixtures and their uploaded R2 objects; preserve real profiles and read history.

Production validation uses public deployment/CORS checks and read-only database diagnostics. Production Google sign-in was confirmed working by the user after configuring a separate Production Web OAuth client; existing Android client IDs remain allowed. The current production domain is `classmatebd.vercel.app`, registered on the Vercel project for future deployments. Supabase's site URL, callback allowlist, Edge Function origins and private R2 CORS use that origin. After moving domains, users sign in and grant browser notification permission on the new origin. Staging Google login and real push display are confirmed. No subscription or Android installation changes were made.

## Android QR download

The desktop sidebar's **Try Android app** option opens a local QR code for `/download/android`. The public server route looks up the latest stable GitHub release and redirects straight to its uploaded APK. GitHub metadata is refreshed within 60 seconds; the redirect itself is not cached, so the QR remains valid after publishing versioned APKs. No GitHub credential is needed for the public repository. Failed lookups show retry and release-page fallback links. The service worker excludes downloads from offline shell handling. Android/browser installation confirmations remain under the user's control.
# Web app installation

Choose **Install web app** on the sign-in page, desktop sidebar or Profile. Chromium's `beforeinstallprompt` opens the native installer when available. If the browser does not offer that event, the app shows platform-specific menu instructions. Android: open the HTTPS site in Chrome and use **Install app / Add to Home screen**; iOS: Safari **Share → Add to Home Screen**. In-app browsers may require opening the site in the regular browser first. Installation cannot be forced by JavaScript. Standalone mode hides the install action. The manifest uses a stable `/` identity and real 192/512 PNG icons; the existing service worker retains academic offline support and push handling.
