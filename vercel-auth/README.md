# ClassMate Firebase role bridge

This Vercel Function replaces the Firebase Cloud Functions `onCreate` trigger
when the Firebase project cannot use the Blaze plan.

`POST /api/firebase-role` accepts only a Firebase ID token in the
`Authorization: Bearer` header. It verifies the token with Firebase Admin,
requires a verified Google account, and assigns `role: "authenticated"` only
to the verified caller's own Firebase user record. Existing custom claims are
preserved.

The function uses Vercel OIDC and Google Workload Identity Federation. It does
not store a service-account private key. Required non-secret Vercel environment
variables:

- `GCP_PROJECT_ID`
- `GCP_PROJECT_NUMBER`
- `GCP_SERVICE_ACCOUNT_EMAIL`
- `GCP_WORKLOAD_IDENTITY_POOL_ID`
- `GCP_WORKLOAD_IDENTITY_POOL_PROVIDER_ID`

Google grants the exact Vercel project/environment subject permission to
impersonate the dedicated service account with short-lived credentials.

## Deployment

The production project is `sum-carry/classmate-auth-bridge`, and its stable
endpoint is:

```text
https://classmate-auth-bridge.vercel.app/api/firebase-role
```

Deploy from this directory after authenticating the Vercel CLI:

```powershell
npm ci
npx vercel deploy --prod --yes
```

The dedicated Google service account should have only the project custom role
containing `firebaseauth.users.get` and `firebaseauth.users.update`. The exact
Vercel production and development OIDC subjects may impersonate it; do not add
a service-account JSON secret as a shortcut.
