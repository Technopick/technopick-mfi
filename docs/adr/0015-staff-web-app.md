# ADR-0015: Staff web app

- Status: Accepted (increment 14, 2026-11-18)

## Decision
- React 19 + TypeScript (strict) + Vite in `apps/staff-web`, as planned in Phase 0. Few runtime
  dependencies: react, react-dom, react-router, oidc-client-ts. Exact versions, lockfile committed.
- **Sign-in**: OIDC authorization code flow with PKCE (public client), tokens in session storage,
  silent renewal. No development bypass. Roles and tenant are read from the access token only to
  shape the UI; the API remains the authority.
- **Runtime configuration** from `/config.json` (public values only), so one build serves every
  environment and tenant.
- **Same-origin hosting**: static files plus a reverse proxy for `/api/` (nginx / IIS), so the API
  needs no CORS and cookies are not used.
- **Contract-first types**: `src/api/schema.d.ts` is generated from the committed OpenAPI contract;
  CI fails if it is stale. Response fields are optional in the contract, so screens handle missing
  fields defensively.
- **Money stays text**: amounts are validated and formatted as decimal strings; never converted to
  JavaScript numbers.
- **Four-eyes in the UI**: approve buttons are hidden on the user's own items with an explanation;
  the API and database still enforce it. Every write carries an idempotency key per user action.
- **Supply chain**: CI runs `npm audit --omit=dev --audit-level=high` on runtime dependencies.

## Not done yet
Browser end-to-end tests against a running core and identity provider; Swahili translation;
accessibility audit with assistive technology; mobile/field apps.
