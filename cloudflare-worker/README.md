# ClassMate secure API

Cloudflare Worker that keeps ClassMate service credentials out of the Android APK. Every non-health route verifies a Firebase Authentication ID token. Privileged routes additionally read the caller's existing `users/{uid}` Firestore document and enforce its role/permissions.

## Configure and deploy

Install dependencies and sign in:

```powershell
cd cloudflare-worker
npm install
node .\node_modules\wrangler\bin\wrangler.js login
```

Set the runtime secrets interactively. Never place their values in `wrangler.jsonc` or commit a `.dev.vars` file.

```powershell
node .\node_modules\wrangler\bin\wrangler.js secret put GEMINI_API_KEY
node .\node_modules\wrangler\bin\wrangler.js secret put GROQ_API_KEY
node .\node_modules\wrangler\bin\wrangler.js secret put GITHUB_LIBRARY_TOKEN
node .\node_modules\wrangler\bin\wrangler.js secret put GITHUB_OWNER
node .\node_modules\wrangler\bin\wrangler.js secret put GITHUB_REPO
node .\node_modules\wrangler\bin\wrangler.js secret put GITHUB_RELEASE_TAG
node .\node_modules\wrangler\bin\wrangler.js secret put TELEGRAM_BOT_TOKEN
node .\node_modules\wrangler\bin\wrangler.js secret put TELEGRAM_CHANNEL_ID
node .\node_modules\wrangler\bin\wrangler.js secret put ONESIGNAL_REST_API_KEY
node .\node_modules\wrangler\bin\wrangler.js secret put ONESIGNAL_APP_ID
node .\node_modules\wrangler\bin\wrangler.js deploy
```

Copy the deployed `workers.dev` URL into the Android project's ignored `local.properties`:

```properties
BACKEND_BASE_URL=https://classmate-secure-api.<your-subdomain>.workers.dev
```

Then rebuild the APK. Rotate all credentials that were present in any previously distributed APK.

## Local development

Copy `.dev.vars.example` to `.dev.vars` and fill in local values. `.dev.vars` is ignored by Git.

```powershell
node .\node_modules\wrangler\bin\wrangler.js dev
node .\node_modules\typescript\bin\tsc --noEmit
```

The explicit `node` commands also work when the project path contains `&`, which can break npm's Windows shim scripts.
