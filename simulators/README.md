# simulators

TEST-ONLY ISO 8583, ISO 20022, Daraja and provider simulators. Never deployable to production.

- `daraja-simulator/` - Safaricom Daraja OAuth, STK Push, STK query and result callbacks.
  Test-scope dependency of the integration runtime only. Standalone use needs
  `DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION`; it binds to 127.0.0.1 and every response
  carries `X-Simulator: TEST-ONLY Daraja simulator`. Receipts start with `SIM`.
  Outcomes by phone-number suffix: `032` cancelled, `001` insufficient funds, `037` unreachable,
  `999` no callback, `222` callback delivered twice, `400` request refused, `500` provider error,
  anything else success.
