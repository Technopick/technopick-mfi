# Contracts

Reviewed, versioned interface contracts. Code must match them; CI fails on drift.

| Contract | File | Checked by |
| --- | --- | --- |
| Core banking REST API v1 | `openapi/core-banking-v1.json` | `AuthorizationApiIntegrationTest.openApiMatchesCommittedContract` |

Changing an API: change the code, run CI, review the generated document printed by the
"Print generated OpenAPI document" step (or `services/core-banking/target/openapi/` locally),
and commit it here in the same pull request. Breaking changes need a new major version path.
