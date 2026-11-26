# ADR-0044: Calling on guarantors and releasing guarantees as loans are repaid

- Status: Accepted (increment 44, 2026-11-26). When guarantors may be called (days past due),
  whether guarantees are released as loans are repaid, and the clearing account are policy and
  accounting decisions for the board and finance; the TEST-ONLY values in tests (30 days,
  release as repaid on, 1190-GUARANTOR-RECOVERIES) are not advice.

## Context
ADR-0043 locked guarantors' pledged savings until a loan closed. A Sacco also needs to recover a
defaulted loan from those pledges, and many release guarantors gradually as the borrower repays,
so a guarantor of a half-repaid loan is not holding the full pledge.

## Decision
- **Settings** (finance; nothing can be called until set): an ASSET recoveries clearing account,
  the days past due from which guarantors may be called (default 90), and whether locks are
  lowered as loans are repaid (default off). The clearing account cannot change once a call exists.
- **A guarantee keeps a lock** (`locked_amount`, on its current EARMARK hold) and what has been
  **recovered** from it. A lock only goes down while the loan is active, always on a new hold that
  matches it; the old hold is released. Recovered rises only through an approved call.
- **A call** (loan officer, branch manager or operations; one waiting per loan): the loan must be
  active and at least the set days past due **as of the last end-of-day close**. The amount
  (default all that can be called) is at most the lesser of what is still locked and what is owed
  (unpaid schedule and penalties, less credit held). It is shared among open guarantees in
  proportion to their locks, rounded to cents, the last taking the remainder.
- **Approval** (branch manager or supervisor, not the requester): re-checks the loan is active and
  the amount still owed. Per guarantor share: the lock goes down by the share (the guarantee is
  CALLED when nothing stays locked), the share moves from the guarantor's savings to the clearing
  account, and a repayment (channel INTERNAL, reference `GUARANTOR:<call>:<guarantee>`) takes it
  from the clearing account into the loan through the ordinary allocation. Rejection needs a reason.
- **Database checks (V41)**: every approved call's lines total the call; each has exactly that
  transfer and that repayment; only these entries post to the clearing account (direct posting
  refused), which therefore nets to zero; the guarantor's savings must have the money available.
- **Release as repaid** (end of day, when on): each open lock is lowered to the pledge's share of
  the principal still outstanding (pledge x outstanding / loan principal), less what has already
  been recovered from it. Locks are never raised. Loan closure still releases everything.
- Limits, own cover and member exit use the current lock, not the original pledge.

## Not built yet
- Recovering from guarantors after write-off (repayment needs an active loan), guarantor
  substitution, notifying guarantors by SMS before and after a call, demand letters, recovering
  from guarantors beyond their pledged savings, and charging the borrower for the guarantors'
  loss (members usually claim it back from the borrower outside the system).

## Consequences
- A guarantor's savings fall by what was called; their statement shows a transfer referencing the
  call. Arrears and provisioning follow the repayment as for any other payment.
- With release as repaid on, guarantors regain use of their savings gradually, overnight.
