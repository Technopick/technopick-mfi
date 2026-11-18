# ADR-0016: Customers and KYC

- Status: Accepted (increment 16, 2026-11-18)
- Context: loans referenced customers by free text; nothing tied a borrower to a verified identity
  or a payout number. Kenya's Data Protection Act 2019 needs a lawful basis (consent recorded) and
  data minimisation; AML practice needs verified identity before lending and controls on payouts.

## Decision
- **Customer record** (`customer.customer`, V15): names, date of birth, identity document
  (national ID, passport or alien ID, format-checked), optional KRA PIN and email, M-Pesa number
  (canonical 2547/2541...), home branch. Numbered `CU<yy>-<7 digits>` per tenant and year.
- **Consent** is required at registration and stored with its wording version (`DPA-2026-01`) and
  time; the staff app shows the wording. Changing the wording means a new version.
- **KYC four eyes**: registered by LOAN_OFFICER or TELLER (PENDING_VERIFICATION), verified by a
  different BRANCH_MANAGER or OPERATIONS user with a note of what was checked (ACTIVE). Database
  enforced, as are: adults only, one customer per document per tenant, identity details frozen
  after verification, consent record immutable, ACTIVE <-> BLOCKED, nothing deleted.
- **Lending**: an application must name an ACTIVE customer by customer number; the application
  and its loan carry the customer id (database checks both).
- **Payout control**: B2C loan disbursements go only to the customer's registered M-Pesa number. The
  number may be omitted (the registered one is used); a different number is refused.
- **Privacy in the API**: search results mask identity numbers and phones; full details only on the
  customer page, for staff roles. Events carry ids only, never personal data.
- Phone normalisation moved to the canonical model (`Msisdn`), shared by customers and payments.

## Not done yet
IPRS (government ID register) check and credit bureau (CRB) lookup; customer detail corrections
after verification (needs its own maker-checker); block / unblock API and screens; PII access
logging and encryption at rest (infrastructure); retention and erasure policy; business and group
customers; documents and photos (object storage).

## Verified (CI)
Core: consent required, minors refused, malformed ID refused, idempotent registration, duplicate
document refused, registrar cannot verify even with the manager role, verification needs a note,
masked search by ID and by name, tenant isolation; application refused for an unverified customer;
loan linked to the customer; payout to another number refused. Database: U0-U8 plus the loan link
(U6). Browser journey: officer registers with consent, manager verifies, then apply -> approve ->
disburse (registered number) -> ACTIVE.
