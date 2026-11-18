# Staff web app

React + TypeScript (Vite) app for branch and back-office staff. It talks only to the core banking
API with the signed-in user's token; every rule (roles, four-eyes, limits) is enforced by the API
and the database, the UI only hides what a user cannot do and explains refusals.

## Screens
- **Worklist**: counts of items waiting for the user's roles.
- **Loan applications**: submit with a repayment quote (LOAN_OFFICER), approve / decline
  (BRANCH_MANAGER, not on own applications).
- **Disbursements**: request M-Pesa B2C disbursement (LOAN_OFFICER, OPERATIONS), approve and send /
  reject (BRANCH_MANAGER, SUPERVISOR), attention list.
- **M-Pesa close-out**: STK and B2C items needing attention, propose PAID / NOT_PAID with evidence
  (OPERATIONS), approve / reject (SUPERVISOR).
- **Suspense**: unmatched paybill receipts, clear to a savings account or loan (OPERATIONS),
  approve / reject (SUPERVISOR).
- **Loan schedule**: lookup by loan number.

## Configuration (runtime, no rebuild per environment)
Serve `config.json` next to `index.html` (see `public/config.example.json`). It holds public values
only: the OIDC issuer URL, client id, scope, the roles claim (`roles`, or e.g.
`realm_access.roles` for Keycloak) and the API base URL (empty = same origin). The app is a public
OIDC client using the authorization code flow with PKCE; register `https://<host>/auth/callback`
as a redirect URI. There is no development sign-in bypass.

## Hosting
Serve the static `dist/` and reverse-proxy `/api/` to core banking on the same origin (nginx on
Linux, IIS with URL Rewrite + ARR on Windows Server), so no CORS is needed. Route unknown paths to
`index.html` (client-side routing). Recommended headers: `Content-Security-Policy: default-src 'self';
connect-src 'self' <oidc-issuer>; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`,
`Referrer-Policy: no-referrer`.

## Development
```
npm ci
npm run dev        # proxies /api to MFI_CORE_URL (default http://localhost:8080)
npm test           # vitest + Testing Library
npm run api:types  # regenerate src/api/schema.d.ts after the OpenAPI contract changes
```
CI checks that the API types match the committed contract, audits runtime dependencies, type
checks, tests and builds on Linux and Windows.

## Known gaps
No end-to-end browser test against a running core + identity provider yet; no Swahili
translation yet; build-time dependency `picomatch` has an open advisory (development tooling only,
not shipped to browsers).
