# TEST-ONLY end-to-end stack

Runs the whole platform locally or in CI with test data and a TEST-ONLY Daraja simulator:
PostgreSQL, RabbitMQ, Keycloak (test realm), core banking, integration, simulator and the staff web
app behind nginx. Every password, secret and key here is a test value. Never point it at real
customers, real money or live payment networks.

## Run
```
mvn -DskipTests package -pl services/core-banking,services/integration,simulators/daraja-simulator -am
mvn -pl simulators/daraja-simulator dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
(cd apps/staff-web && npm ci && npm run build)
docker compose -f infrastructure/e2e/docker-compose.yml up -d --build --wait
docker compose -f infrastructure/e2e/docker-compose.yml --profile seed run --rm seed
```
Open http://localhost:8088. Test users (password `e2e-only-<user>`): `officer` (LOAN_OFFICER),
`manager` (BRANCH_MANAGER), `supervisor` (SUPERVISOR), `ops` (OPERATIONS). Product `BIASHARA`,
branch `NBO01`. Stop with `docker compose -f infrastructure/e2e/docker-compose.yml down -v`.

Browser test: `cd apps/staff-web && npx playwright install chromium && npm run e2e`.

## How it is wired
- Keycloak issues tokens with the browser-facing issuer `http://localhost:8180/realms/technopick`;
  core validates that issuer and fetches keys from `keycloak:8080` inside the network. Staff tokens
  carry `roles` and `tenant_id` (hard-coded test tenant); the integration runtime uses the
  `mfi-integration` client-credentials client with role INTEGRATION.
- The simulator generates a key pair at start and writes only the public key to a shared volume;
  the integration runtime reads it as `mfi.daraja.certificate-pem` (Spring config tree), so no
  private key is ever committed.
- Callbacks are accepted only from the stack's private subnet `172.30.57.0/24`.
- Seed data (`seed/seed-e2e-TEST-ONLY.sql`): one fictitious institution, business date = today.
