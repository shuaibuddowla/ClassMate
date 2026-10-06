# ClassMate: access and operations handoff

Prepared 6 October 2026, Asia/Dhaka. Public identifiers and credential locations only; no passwords, bearer tokens, service-role keys or private keys are included. This document is for another agent working with the user, not a new authorization to publish, delete data or change billing.

## 1. Workspace and current state

- Windows workspace: `D:/Code & Scripts/ClassMate-main`; shell: PowerShell. Quote paths containing spaces and `&`.
- Repository: `https://github.com/shuaibuddowla/ClassMate`.
- Branch: `codex/v2-schedule-library`. Last observed HEAD: `5a3ec94`, committed/pushed.
- Latest GitHub release freshly checked: **v1.1.26**, published 6 October 2026, assets `classmate-1.1.26.apk` and `update.json`.
- Android source: **1.1.27, versionCode 28**, package `com.shuaib.classmate`. Source version is not proof that an APK has been built or published.
- Current uncommitted changes observed: `web/src/app/globals.css` and `web/src/components/app.tsx`. Preserve and inspect these; another process/user may be editing them.
- Untracked items observed: `deliverables/`, `scripts/finish_classmate_library.py`, `scripts/finish_classmate_screens.py`, `scripts/finish_notice_reminders.py`, `scripts/update-cse22-routine.py`. Do not overwrite/delete/commit these without understanding them.
- Read `docs/AGENT_HANDOFF_2026-10-05.md` for the broad feature inventory, but its release/version/current-task section is outdated. Later source and migrations override older statements.
- User wants fast execution, concise updates, polished native/mobile web UI, preservation of working logic, staging for backend verification, and no repeated permission requests for already authorized actions.

## 2. Architecture and public identifiers

| Service | Identifier / URL |
| --- | --- |
| Production web | https://classmatebd.vercel.app |
| Staging web | https://classmate-web-staging.vercel.app |
| Production Supabase ref | `cbskpifgbdbgdmnnxfrh` |
| Production Supabase URL | https://cbskpifgbdbgdmnnxfrh.supabase.co |
| Staging Supabase ref | `qpryxvunhebwljlwmggk` |
| Staging Supabase URL | https://qpryxvunhebwljlwmggk.supabase.co |
| PostgreSQL schema | `classmate` |
| Firebase / Google Cloud project | `classmate-a016a` |
| GitHub repo | `shuaibuddowla/ClassMate` |
| Android update metadata | https://github.com/shuaibuddowla/ClassMate/releases/latest/download/update.json |
| Vercel production project | `classmate-web` |
| Vercel staging project | `classmate-web-staging` |
| Vercel team slug | `sum-carry` |
| Support / developer contact | `shuaibuddowla.personal@gmail.com` |

Android uses native Kotlin/XML/View/Material components. Active academic UI is `ClassMateAcademicScreensSupabase.kt`, not the legacy Firebase screens. Web lives in `web/`: Next.js 16, React 19, TypeScript, TanStack Query, Supabase JS, Firebase messaging, PWA worker and Playwright.

Supabase is the authoritative database and auth backend. Firebase supplies Google integration and FCM delivery, not the new academic database. Private academic files are in Cloudflare R2; backend functions issue permission-checked temporary URLs. APKs are on GitHub Releases. Gemini runs behind a Supabase Edge Function.

Never put service-role, FCM private keys, R2 secrets, Gemini secrets or management tokens in browser/Android code. Client-visible Supabase public keys and Firebase web config do not grant administrative authority.

## 3. Credential locations and what to ask for

The following files existed in this workspace during inspection and are ignored/private configuration. Read only what is needed; do not print whole files, put values in chat, commit them, or include them in screenshots/logs.

| Access | Existing configuration | If unavailable on the next agent's machine |
| --- | --- | --- |
| GitHub | Existing `gh` CLI authentication; Git remote credentials | Ask user to run `gh auth login` with repository release access |
| Vercel | Existing Vercel CLI session; `web/.vercel/project.json` project link | Ask user to sign in through `vercel login` and grant project access |
| Supabase management | Windows Credential Manager entry `Supabase CLI:supabase`; or `SUPABASE_ACCESS_TOKEN` | Ask user to run `supabase login`, or configure a personal access token locally |
| Android public/backend config | `local.properties`: SDK, Supabase public URL/key, staging URL/key, update URL and legacy integration settings | Ask for equivalent public config and local SDK setup |
| Android release identity | `keystore.properties`: `storeFile`, `keyAlias`, `releaseCertSha256`; referenced production keystore | Ask for the existing production keystore securely; never generate a replacement key to update existing installs |
| Android signing passwords | `CLASSMATE_STORE_PASSWORD`, `CLASSMATE_KEY_PASSWORD` environment variables; not present in inspected keystore properties | Ask user to enter these locally, or let them sign through Android Studio |
| Web environment | `web/.env.local`: `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`, `NEXT_PUBLIC_FIREBASE_CONFIG`, `NEXT_PUBLIC_FIREBASE_VAPID_KEY`, temporary `VERCEL_OIDC_TOKEN` | Pull correct Vercel project environment; do not reuse staging values for production |
| Release/queue secret | `supabase/.classmate-secrets.local.env`: `CLASSMATE_DISPATCH_SECRET` | Ask user to configure the matching server dispatch secret locally; never rotate it casually |
| Cloudflare R2 | Supabase Edge Function secrets: `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | Ask for Cloudflare account/bucket access only if storage configuration must change |
| FCM server signing | Supabase secret `FCM_SERVICE_ACCOUNT_JSON` | Ask for Firebase service-account configuration securely if missing; private key stays server-side |
| Gemini | Supabase secrets `GEMINI_API_KEY_1`, `_2`, `_3`; optional `GEMINI_QUOTA_GROUP_1/2/3`, `GEMINI_MODEL` | Ask user to enter keys in Supabase secrets; same-project keys share quota |
| OAuth | Google Cloud web/Android OAuth clients; Supabase Google provider settings | Ask for dashboard access or manual configuration; keep client secrets out of chat |

Not all server secrets were freshly inspected for presence. The names above come from active source. Do not claim possession of their values. A hosted agent cannot assume this laptop's credential store or login sessions are accessible.

## 4. Supabase operations

`scripts/capacity/platform_access.py` is the existing Windows helper. It reads `SUPABASE_ACCESS_TOKEN` if supplied, otherwise the Supabase CLI token from Windows Credential Manager, without printing it.

- `management(path, method='GET', body=None)` calls `https://api.supabase.com/v1/` with authenticated headers.
- `query(ref, sql)` uses the Management API database query endpoint for the explicitly selected project.
- `api_keys(ref)` returns project keys internally; do not dump that return value.
- Constants `STAGING` and `PRODUCTION` contain the refs above.

Historical deployment helpers in `scripts/web/` apply specific migrations and register migration history. They are not general-purpose scripts for every future change. Read exact scope first. Example: `deploy-teacher-experience.py staging` applies only migrations `202610060004_teacher_experience` and `202610060005_timetable_cancellation_projection`; it was previously used for staging then production.

For new work, write a versioned migration, verify against staging/isolated fixtures, preserve RLS/RPC authorization, then apply to production through the project's established migration process. Do not run a blanket `db push` without checking linked ref and migration history. A CLI route, when installed/authenticated, is `supabase functions deploy <function> --project-ref <ref>`; follow `supabase/config.toml` and the function's own authorization checks. Functions marked `verify_jwt=false` must still authenticate/authorize internally.

Important functions: `classmate-ai`, `dispatch-classmate-notifications`, `publish-classmate-release`, `signed-classmate-resource`, `upload-classmate-resource`, `cleanup-classmate-resources`, `test-classmate-web-push`. `CLASSMATE_WEB_ORIGINS` controls approved web CORS origins. Keep existing APK contracts backward compatible.

## 5. Web build and Vercel deployment

Read `web/AGENTS.md` and the relevant bundled Next.js guide under `web/node_modules/next/dist/docs/` before editing. Do not rely on older Next API assumptions.

From `web/`: `npm run typecheck`, `npm test`, `npm run build`. The build script bundles the service worker before Next's webpack build. Use Playwright for browser verification where useful; do not capture real auth tokens in shared traces.

Vercel CLI on this laptop:

```powershell
node C:/Users/USER/AppData/Roaming/npm/node_modules/vercel/dist/index.js link --yes --project classmate-web-staging --cwd web
node C:/Users/USER/AppData/Roaming/npm/node_modules/vercel/dist/index.js deploy --prod --yes --cwd web
# Verify staging, then:
node C:/Users/USER/AppData/Roaming/npm/node_modules/vercel/dist/index.js link --yes --project classmate-web --cwd web
node C:/Users/USER/AppData/Roaming/npm/node_modules/vercel/dist/index.js deploy --prod --yes --cwd web
```

`link` refreshes `web/.env.local`. `--prod` on the staging project publishes its staging alias, not ClassMate production. Always select the correct project and use `--cwd web`. Wait for READY/alias success, then verify the stable URL. Production's obsolete URL `classmate-web-mu.vercel.app` must not be restored as the push origin.

## 6. Android build and GitHub publication

Java: `C:/Program Files/Android/Android Studio2/jbr`. SDK tools: `$env:LOCALAPPDATA/Android/Sdk/`. Python helper runtime: `C:/Users/USER/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`.

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio2/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest -PclassmateTarget=production --console=plain
```

Debug verification is not a signed release. User frequently builds the signed APK in Android Studio and supplies `release/app-release.apk`; another output is `app/build/outputs/apk/release/app-release.apk`. Inspect actual artifact version/package/certificate; Android Studio's successful build message proves none of those match the installed app.

Release script:

```powershell
./scripts/publish-classmate-github-update.ps1 -ApkPath 'D:/Code & Scripts/ClassMate-main/release/app-release.apk' -ValidateOnly
# After validating artifact, committed/pushed release source and user authorization:
./scripts/publish-classmate-github-update.ps1 -ApkPath 'D:/Code & Scripts/ClassMate-main/release/app-release.apk' -ReleaseNotes @('Describe the actual changes')
```

It checks package, numeric version, non-debug single signer, expected production SHA-256 certificate, embedded GitHub updater source, file size/hash, clean release source, pushed HEAD, unused tag/version and monotonic versionCode. It creates a draft, uploads `classmate-<version>.apk` plus `update.json`, checks asset size/digest and target commit, then publishes as latest. It calls `notify-classmate-release.ps1` to queue an idempotent announcement through Supabase using `CLASSMATE_DISPATCH_SECRET`.

If announcement fails, retry `notify-classmate-release.ps1 -VersionName <actual-version> -VersionCode <actual-code>`; do not create another release. Queued/FCM-accepted does not prove phone display.

The updater may automatically download a verified newer APK, but installation requires user consent and Android confirmation. Debug and production certificates cannot update one another. Never bypass signature checks, downgrade internal versionCode, silently replace the production keystore or uninstall users' signed apps/data to hide a signing error.

An earlier user-authorized legacy-link replacement made the v1.1.25 download serve newer APK bytes while preserving the old shared link. Do not repeat this automatically: inspect aliases/metadata, keep actual APK version intact and verify hashes. Canonical latest release should remain the real version.

ADB: enumerate connected devices each time; wireless transport changes. Do not install unless requested, and use the signed artifact if that is what the user requested.

## 7. Current feature constraints

- Students: university email and server-detected batch; session year 25 displays `24-25`.
- Teachers: administrator-approved Gmail/university email, assigned-batch chooser, familiar layout, full selected-batch timetable, assignment-scoped library/posting. Teacher notices show their own posts; no student onboarding/notification prompts or unread notice badges.
- Owners/CRs: server-authorized management. CR notice/timetable changes scoped to their batch; buses shared university-wide. Ordinary users have no AI writes. AI tab hidden for now; authorized compose/tools retained.
- Notices load ten per page, social comments/likes/read receipts, translations, cancellation projection. Notification routing supports Android and web independently; `/silent` remains feed-only.
- Friends batch-scoped; owner appears first with Admin badge only in the owner's home batch. About Developer remains available separately with GitHub and portfolio links.
- Library private R2 objects; desktop opens file in new tab, mobile current tab; selected course opens a central course-files page.
- Calendar drives Thursday/Friday weekends plus actual class/office holidays. Do not hardcode screenshot dates.
- Web offline content is account/batch-scoped academic data, excluding Friends contacts and signed file URLs; clear private caches on logout.
- Most recent profile change removes Current mess/flat input from completion/editing on Android and web, preserving existing saved values. Web deployed; Android source compiled, not released as 1.1.27 yet.

## 8. Current OAuth branding task

Public pages deployed: `/about`, `/privacy`, `/terms`; sign-in page links them. Landing compacted and checked at mobile/desktop sizes. Support email listed above.

Root Next metadata has public Search Console verification value `N1jaz91POPN5mOXCtWpSiuQUC1Z5dIon3079aDpR1SE`. It is a public ownership token, not an API credential. User's screenshot confirms URL-prefix property `https://classmatebd.vercel.app/` is verified. Google branding still rejects homepage ownership.

Latest researched Google troubleshooting guide specifies DNS-verified Domain property, rather than URL-prefix verification, and a project-owner account. Therefore the HTML-tag advice was acknowledged as incomplete for the review requirement. No official blanket Vercel ban was found; don't promise approval. User has not bought a custom domain. Options discussed: request additional review with screenshot, or use a custom domain under user-controlled DNS while retaining Vercel hosting. No purchase/billing change is authorized.

Official references:
- https://support.google.com/cloud/answer/13804266?hl=en
- https://support.google.com/cloud/answer/13807376
- https://support.google.com/cloud/answer/15549049?hl=en

Do not rotate the currently working Google client/secret to fix display branding. Web Google callback is each Supabase project's `/auth/v1/callback`; application return route is `/auth/callback` in Supabase's allowlist. Android has its own authorized signing/client setup. Ask the user for current dashboard configuration if needed; never paste secrets in the handoff.

## 9. Next-agent first steps

1. Check `git status`, current source version, GitHub latest release and requested task before changing anything.
2. Read this handoff plus the relevant active source/docs; older summary data may be stale.
3. Test existing CLI authentication before asking for credentials. Request only missing access for the task, preferably through local login/configuration.
4. Preserve pending changes and server permissions; do not publish secrets or private test fixtures.
5. Report what changed, what was actually tested/deployed, and what still needs user action. Do not claim signed publication, phone installation, notification display or Google approval from build/API success alone.

## 10. Release 1.1.27 preparation (later on 6 October)

- Fixed welcome-screen initialization order and AI composer parent attachment; Android debug build and 38 unit tests passed.
- New logo integrated, logo orbits removed, light is the default and the user's theme choice persists.
- About Developer redesigned, Facebook removed. Migration `202610060006` restricts owner roster visibility to the home batch; applied and verified in staging and production using rollback-only tests.
- Web deployed to `classmatebd.vercel.app`; 25 unit tests and developer-panel/theme browser tests passed.
- User supplied `release/app-release.apk`: verified version 1.1.27 (28), valid production signing identity. Publication is being performed from this source snapshot; check GitHub for final status.
