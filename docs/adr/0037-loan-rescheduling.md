# ADR-0037: Loan restructuring and partial prepayment

- Status: Accepted (increment 37, 2026-11-24). The restructuring policy (who may restructure, how
  often, limits on term, whether arrears may be capitalised, fees) needs credit and compliance
  sign-off; the SASRA treatment of restructured loans in the prudential return is not built.

## Decision
- **One mechanism: a new schedule version**, requested by a loan officer or branch manager with a
  reason and approved by a branch manager or supervisor who did not request it, on the same
  business date (a request from an earlier day must be made again, because the loan has moved
  on). One open request per loan.
- **What may change** (the database checks every new version, V34): instalments due on or before
  the change keep their due date and interest and never carry less principal than was paid on
  them; the instalment accruing interest at the change keeps its due date and interest (its daily
  accrual depends on both); no instalment's interest falls below what accrued or was paid on it,
  nor its principal below what was paid; instalments with payments, accruals or penalties stay;
  due dates rise with the instalment number. New versions exist only for a settlement or an
  approved reschedule.
- **Restructure**: unpaid principal, including overdue principal, is planned again with the
  product's own method and rate over a chosen number of instalments, optionally after
  interest-only grace months. Overdue interest and penalties stay due now (capitalising them would
  need a principal increase and is left to finance policy). The loan is flagged restructured on
  its schedule and in the PAR list, and stays at least in IFRS 9 stage 2 for
  `restructure_probation_months` (default 6) after the restructure.
- **Partial prepayment**: only without arrears and with at least that much credit on the loan.
  The amount is added to the next instalment's principal and paid from the member's credit on its
  due date (so interest falls from then on); the rest is planned again to shorten the term (fewest
  instalments that keep the instalment no higher) or lower the instalment (same number).
- **The member is told by SMS** (new SERVICE category, always sent: it changes their contract).

## Not built yet (needs decisions)
- Capitalising arrears, restructure fees, limits on how often a loan may be restructured, the
  SASRA classification of restructured loans, a probation rule based on performance (now time
  only), and prepayment taking effect before the next due date.
