# ADR-0014: Loan origination

- Status: Accepted (increment 13, 2026-11-18)
- Context: loans could only be created by seeding. Origination must enforce product limits and
  four-eyes approval, and hand over cleanly to disbursement (ADR-0012) and repayment (ADR-0010).

## Decision
- **Application** (`POST /api/v1/lending/applications`, LOAN_OFFICER): customer reference, product
  code, branch, principal, term, optional first due date (default business date + 1 month; must be
  after the business date and within 60 days), purpose. Idempotent by key. The product's ACTIVE
  version is **pinned** at submission and checked against its amount/term limits. The response
  carries a repayment **quote** (every installment, upfront fee, net disbursement, total interest,
  total repayable) computed by the same schedule calculator the loan will use.
- **Decision** (`/approve`, `/decline` with reason, BRANCH_MANAGER who is not the submitter).
  Approval, in one transaction: allocates the loan number, creates the loan (submitter as
  creator, decider as approver - the existing loan rule `approved_by <> created_by` applies),
  writes schedule version 1 (due dates monthly from the first due date), marks the application
  APPROVED with its loan, and records `LoanApproved` in the outbox. Approval is refused if the
  pinned product version is no longer ACTIVE or the first due date has passed (resubmit).
- **Database rules (V14)**: different submitter and decider; submitted terms and pinned version
  never change; SUBMITTED -> APPROVED | DECLINED | WITHDRAWN, final; an APPROVED application must
  point to a loan with exactly its terms, created by the submitter and approved by the decider;
  decline needs a reason; nothing deleted.
- **Loan numbers**: `LN<yy>-<7 digits>` (e.g. `LN26-0000001`), per tenant and year, allocated under
  a row lock (no gaps, no duplicates under concurrency), 12 characters so they fit the M-Pesa STK
  AccountReference and paybill BillRefNumber.
- **Schedule**: `GET /api/v1/lending/loans/{loanNumber}/schedule` (latest schedule version).

## Not done yet
Customer/KYC master data (customer reference is free text today); credit scoring and CRB checks;
approval limits by amount (e.g. credit committee above a threshold); withdrawal by the officer
(supported by the database, no API yet); collateral and guarantors.

## Verified (CI)
Core end-to-end with no seeded loan: role checks, product limits, first-due-date rule, sub-cent
amounts, quote figures (EMI 9,455.96 on 100,000 / 12 months / 24% reducing; fee 2,000; net
98,000; schedule closes at zero), idempotent replay and key reuse conflict, submitter cannot
approve even with the manager role, decline needs a reason, loan number format, schedule dates,
loan maker/checker recorded, decline keeps no loan; then B2C disbursement of the new loan and a
paybill payment by its loan number. Database: 3 checks (A0-A5).
