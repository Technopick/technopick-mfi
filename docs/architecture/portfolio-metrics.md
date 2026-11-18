# Portfolio metrics - definitions (arrears/v1, par/v1, penalty/v1)

Status: DRAFT for finance and compliance review. Implementations: `ArrearsCalculator`,
`PortfolioAtRisk`, `PenaltyRule`, `EndOfDayPlanner`; SQL view `lending.par_by_branch`.

## Days past due (arrears/v1)
- An installment is overdue on date D when its due date < D and unpaid principal + interest
  exceeds the product tolerance (default 0). Penalties and fees alone never age a loan.
- DPD = D - due date of the oldest overdue installment (unpaid yesterday = 1 DPD).
- Arrears amount = unpaid principal + interest of all overdue installments.

| Bucket | DPD | Classification (to confirm per regulator) | DPD |
| --- | --- | --- | --- |
| CURRENT | 0 | NORMAL | 0-30 |
| DPD_1_30 | 1-30 | WATCH | 31-90 |
| DPD_31_60 | 31-60 | SUBSTANDARD | 91-180 |
| DPD_61_90 | 61-90 | DOUBTFUL | 181-360 |
| DPD_91_180 | 91-180 | LOSS | > 360 |
| DPD_OVER_180 | > 180 | | |

## Portfolio at risk (par/v1)
PAR_n = outstanding principal of loans with DPD > n / outstanding principal of all loans.
- As-of: the arrears snapshot date written by EOD (one snapshot per ACTIVE loan per date).
- Exclusions: written-off and closed loans (not ACTIVE, so never snapshotted).
- Whole outstanding principal of a late loan counts. One currency per figure; ratio at
  4 decimal places HALF_EVEN; no loans gives NULL, never a fake 0%.
- Worked example (tested): portfolio 200,000 with loans at 0/5/45/120 DPD of
  100k/50k/30k/20k gives PAR1 0.5000, PAR30 0.2500, PAR60 0.1000, PAR90 0.1000.

## Penalties (penalty/v1)
- Nothing until grace days have passed after the due date.
- FLAT_ONCE: one charge per late installment. DAILY_RATE: each business day, rate x that
  day's unpaid principal + interest of the installment. Optional cap per installment.
- LEGAL REVIEW: caps on interest/charges for non-performing loans (e.g. in duplum for banks and
  microfinance banks) and the NDTCP Regulations 2026 must be confirmed per licence type.

## End of day
- One business date per tenant; it advances by exactly one day and only after that date's
  EOD run COMPLETED (database-enforced), so every date is processed once and in order.
- Per loan per date: one accrual entry (`loan:<id>:ACCRUE:<date>`), one penalty entry per
  late installment (`loan:<id>:PENALTY:<n>:<date>`), one arrears snapshot. Re-running a date
  after a crash re-derives the same keys, so nothing is posted twice.
