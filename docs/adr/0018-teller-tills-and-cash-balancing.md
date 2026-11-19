# ADR-0018: Teller tills and end-of-day cash balancing

- Status: Accepted (increment 18, 2026-11-19)

## Decision
- **Tills have their own GL account.** Each till (`savings.till`) belongs to a branch and currency
  and has a dedicated cash GL account. The branch vault stays `branch_cash_account.gl_cash`, which
  now also names the teller shortage (expense) and overage (liability) accounts.
- **Sessions.** A TELLER opens a till session for the business day. The database allows at most one
  open (or closing) session per till and per teller.
- **Cash only through your own open till.** Teller deposits and withdrawals post Dr/Cr the till GL
  (no longer the branch vault). The database refuses a teller transaction unless the requester has
  an OPEN session at the account's branch and currency (`TELLER_NO_OPEN_TILL`, `TELLER_TILL_MISMATCH`).
  A withdrawal is also refused when the till holds less cash than the payout.
- **Vault transfers are maker-checker.** The teller requests VAULT_TO_TILL or TILL_TO_VAULT on
  their own open session; a BRANCH_MANAGER or SUPERVISOR who is not the requester approves (posts
  Dr till / Cr vault or the reverse) or rejects with a reason. Idempotent by key.
- **Closing count.** The teller enters the counted cash; expected cash is the till GL balance from
  the ledger. A zero difference closes at once. Otherwise the session goes to CLOSING and a
  different person either accepts the difference with a note (shortage: Dr shortages / Cr till;
  overage: Dr till / Cr overages; session CLOSED) or sends it back for a recount (session OPEN).
  The database refuses to close while teller transactions or transfers are still pending, refuses
  a variance close without a different approver, a reason and a posted entry, and treats closed
  sessions as final.
- **Events.** `TillClosed` is recorded in the outbox with the variance.

## Consequences
- After an accepted variance the till GL equals the count, so the next session opens with the
  physically held cash as its expected balance.
- Branches must configure tills, till GL accounts and the shortage/overage accounts before tellers
  can transact (operations set-up; seeded only for TEST-ONLY environments).
- Not yet built: teller-level cash limits (maximum till holding), denomination counts, branch vault
  count and head-office cash orders, and forced end-of-day close of sessions left open.
