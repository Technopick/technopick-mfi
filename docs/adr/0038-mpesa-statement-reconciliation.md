# ADR-0038: M-Pesa statement reconciliation

- Status: Accepted (increment 38, 2026-11-24). The statement layout is the M-Pesa business portal's
  CSV export as commonly described; it must be confirmed against a real export from the Sacco's
  own paybill and B2C accounts before go-live. Who may sign off, the daily deadline and how long
  differences may stay open are finance policy decisions.

## Context
The platform records every M-Pesa receipt (C2B and STK) and every B2C disbursement against a
clearing account (ADR-0008, -0010, -0012). Safaricom's statement is the independent record of
what actually moved, and it also shows things the platform never sees: transaction charges,
transfers to the bank, float top-ups and reversals. Until now nothing compared the two.

## Decision
- **One statement per M-Pesa account per calendar day.** Finance (role FINANCE) uploads the
  portal CSV for a paybill or B2C short code and a date. The file is read by column name; every
  line must be completed on that date in the tenant's time zone; completed lines need a balance.
  Opening and closing balances are derived from the lines; if the running balances do not chain
  in either file order, the upload is flagged but accepted. The same file (SHA-256) or a second
  statement for the same day is refused; a wrong upload is **withdrawn** (with a reason), never
  deleted, which releases its matches.
- **Privacy**: other parties' phone numbers are masked (2547\*\*\*\*678) and names after them are
  dropped, in both "Other Party Info" and "Details". Account references after "Acc." are kept.
- **Matching** (automatic on upload, and again on request): a completed paybill receipt line
  matches the payment with the same receipt number and short code; a B2C payment line matches the
  COMPLETED disbursement with the same transaction id and short code. The amounts must be equal,
  and each payment or disbursement is matched by at most one live line (database unique index).
  Anything else is an exception with a reason: NOT_IN_LEDGER, AMOUNT_MISMATCH,
  DISBURSEMENT_ATTENTION (the disbursement is not completed or is flagged), ALREADY_MATCHED.
  Lines are categorised (receipt, payment, charge, transfer, funding, reversal) from the reason
  type and details, to guide finance.
- **Resolving exceptions**: finance links one or more lines to the journal entry that accounts
  for them (for example a manual journal for the day's charges or the transfer to the bank); the
  database requires the linked lines to add up to exactly what that entry moved on the clearing
  account, and refuses entries that belong to an M-Pesa payment or disbursement (those are
  matched, not linked). Otherwise finance explains the line in writing (at least 10 characters).
  Who resolved each line, and when, is kept.
- **The ledger side**: the clearing account's balance at the end of the statement date is
  compared with the statement's closing balance, and every entry on the clearing account dated
  that business date that no live line accounts for is listed ("on the ledger, not on the
  statement" - usually receipts that appear on the next day's statement).
- **Sign-off** (FINANCE or SUPERVISOR) needs: the day closed by end of day; no open exception;
  a signer who neither uploaded the statement nor resolved any of its lines; and a note when
  there is a balance difference or unmatched ledger entries. The database re-derives the ledger
  balance and the unmatched entries at sign-off and refuses stale figures (RECON_STALE). A
  signed-off day never changes.
- **Database rules** (V35, R1-R5) back all of this, as for every other money flow.

## Consequences
- Charges and bank transfers are still booked by manual journal (maker-checker, ADR-0025); the
  reconciliation shows when they are missing and links them once booked. Automatic posting of
  charges from the statement is not built.
- Matching is by receipt number, so a receipt applied after the statement was uploaded is found
  by "Match again"; a day can be signed off with timing differences explained in the note.
- If a paybill and a B2C account share one clearing account, the balance comparison mixes both;
  separate clearing accounts are recommended.
- Statements are uploaded by hand. Pulling them from Safaricom (statement API or e-mail) needs the
  production integration and credentials, and is out of scope.
- A day with no completed transactions cannot be uploaded (there is nothing to match); it needs no
  reconciliation beyond the clearing balance, which the trial balance shows.
