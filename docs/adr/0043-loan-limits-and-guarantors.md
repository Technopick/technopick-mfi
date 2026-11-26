# ADR-0043: Loan limits and guarantors

- Status: Accepted (increment 43, 2026-11-26). The multiple, what counts as own cover, and the
  guarantee rule are credit policy decisions for the board; the TEST-ONLY rule in tests (3 x own
  cover, shares counted, other loans deducted, guarantees above own cover) is not advice.

## Context
Saccos lend against members' deposits: a member may usually borrow a multiple of what they have
saved, and the part of a loan above their own deposits is secured by guarantors, whose guaranteed
amounts are frozen in their own deposits until the loan is repaid.

## Decision
- **Limit rules per loan product** (finance; off unless set): a multiple of own cover (up to 20),
  whether shares count, whether other loans' outstanding principal is deducted, and whether
  guarantees must cover the part above own cover. A rule can be removed.
- **Own cover** = the member's savings (open accounts, ledger balance) less what they have pledged
  for other members, plus shares if counted. **Limit** = multiple x own cover, less outstanding
  principal on their approved and active loans if deducted (not below zero).
- **Checked twice**: at submission (refused above the limit, with the figures) and at approval with
  the values then (the service explains; the database re-checks as V40 LG2).
- **Guarantees** (loan officer or branch manager, while the application is open): another active
  member, one of their own active savings accounts, an amount they have available, and a signed
  consent reference (the guarantee form). The amount is locked by an EARMARK hold on that account,
  which nothing else can release. One pledge per guarantor per application; a pledge can be
  withdrawn, with a reason, only while the application is open.
- **Release**: automatically when the application is declined or withdrawn, and when the loan
  closes (fully repaid). Approved guarantees cannot be withdrawn.
- A guarantor with open guarantees cannot leave the Sacco (member exit names them), and their
  pledges reduce their own cover for borrowing.

## Not built yet
- Recovering a defaulted loan from guarantors (attaching their pledged savings), releasing
  guarantees in proportion as the loan is repaid, guarantor substitution, limits on how much one
  member may guarantee in total, guarantor SMS consent, collateral other than deposits, minimum
  membership period before borrowing, and limits by amount for approvers.
- Written-off loans keep their guarantees locked until a recovery or release process exists.

## Consequences
- Members' deposits now drive what they may borrow; guarantors see the pledged amount unavailable
  in their savings while the loan runs.
