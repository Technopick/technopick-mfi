# Daraja sandbox: adding credentials and running the smoke test

Credentials are entered by you in GitHub, never in chat, tickets or the repository. Nobody can
read a secret back from GitHub once saved, and GitHub masks secret values in logs.

## 1. Create a sandbox app (Safaricom developer portal)
Create an app with the "Lipa na M-Pesa Sandbox" and "M-Pesa Sandbox" APIs. Note the consumer key and
secret. The sandbox test credentials page lists the STK short code and passkey, the B2C short code,
the initiator name and initiator password, and a test MSISDN. Download the sandbox certificate
(PEM text).

## 2. Add the secrets (GitHub)
Repository Technopick/technopick-mfi -> Settings -> Environments -> New environment
`daraja-sandbox` (optionally require a reviewer) -> Environment secrets:

| Secret | Value |
| --- | --- |
| `DARAJA_SANDBOX_CONSUMER_KEY` | app consumer key |
| `DARAJA_SANDBOX_CONSUMER_SECRET` | app consumer secret |
| `DARAJA_SANDBOX_PASSKEY` | Lipa na M-Pesa Online passkey (sandbox) |
| `DARAJA_SANDBOX_SHORTCODE` | STK short code (default 174379 if omitted) |
| `DARAJA_SANDBOX_TEST_MSISDN` | sandbox test number (default 254708374149 if omitted) |
| `DARAJA_SANDBOX_CALLBACK_BASE_URL` | any https URL you control; callbacks are not checked by the smoke test |
| `DARAJA_SANDBOX_B2C_SHORTCODE` | B2C short code (optional; default 600999) |
| `DARAJA_SANDBOX_B2C_INITIATOR_NAME` | B2C initiator name (optional, enables the B2C check) |
| `DARAJA_SANDBOX_B2C_INITIATOR_PASSWORD` | B2C initiator password (optional) |
| `DARAJA_SANDBOX_CERTIFICATE_PEM` | sandbox certificate PEM text (optional) |

## 3. Run
Actions -> daraja-sandbox -> Run workflow. The job prints only outcome kinds, codes and
descriptions. Tests whose secrets are missing are reported as skipped, not passed.

## What it proves, and what it does not
Proves: OAuth with your app, STK Push password construction accepted, STK query answered, and (if
configured) the B2C SecurityCredential encryption and request accepted by Safaricom's sandbox.
Does not prove: callbacks reaching the platform (needs a deployed, publicly reachable integration
runtime), production behaviour, or production credentials. Production needs a separate go-ahead.
