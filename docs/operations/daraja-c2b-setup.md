# Daraja C2B setup (per tenant paybill)

1. Generate a random path token (at least 32 characters) in the secret store.
2. Configure the integration runtime (environment / secret store, never committed):
   `mfi.daraja.paybills[n].token`, `.tenant-id`, `.short-code`, and `mfi.daraja.allowed-cidrs`
   with Safaricom's current callback source ranges from Safaricom's documentation.
3. In core banking, as platform admin: create GL accounts for paybill clearing and suspense and a
   `payments.mpesa_paybill` row (short code, branch, GL accounts, maximum amount).
4. Register URLs with Daraja (sandbox first):
   `https://<integration-host>/daraja/c2b/<token>/validation` and `.../confirmation`.
5. Test in sandbox: known account -> credited; unknown reference -> rejected at validation (or
   suspense if validation is not enabled on the short code); duplicate confirmation -> one posting.
6. Monitor: callbacks with status FAILED (dead letters), suspense balance and ageing (with the
   ACCEPT validation fallback, core outages can push mistyped references into suspense),
   `mfi.holds.expired`.

Production registration, certification and Safaricom approvals are pending (not implied by tests).
