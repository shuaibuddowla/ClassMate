# ClassMate Web

Responsive Next.js application backed by the existing ClassMate Supabase system.

See [`docs/CLASSMATE_WEB.md`](../docs/CLASSMATE_WEB.md) for configuration, deployment, verification and browser limitations.

```powershell
npm ci
npm run dev
npm run typecheck
npm test
npm run build
```

Authenticated staging browser tests require isolated fixtures prepared with `scripts/web/verify.py`. Never run synthetic fixture scripts against production.
