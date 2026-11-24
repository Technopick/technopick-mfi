# ADR-0036: IFRS 9 expected credit loss provisioning for loans

- Status: Accepted (increment 36, 2026-11-24). Rates, stage thresholds and the exposure base
  need finance sign-off (and the auditors' agreement) before the first posted run.

## Decision
- **Simplified approach (provision matrix).** At a closed month end each loan with principal
  outstanding is staged from its end-of-day arrears snapshot: stage 2 above
  `stage2_dpd` days past due (default 30, the IFRS 9 rebuttable presumption), stage 3 above
  `stage3_dpd` (default 90). Its expected credit loss is outstanding principal x the rate finance
  set for its product and stage (or the default '*' row), rounded to cents per loan.
- **Maker-checker runs.** A finance user requests a run for a month end (the day must be closed by
  end of day; runs go forward month by month). It records every loan's figures. A second finance
  user approves it, which posts, dated the month end and branch by branch, only the change from
  what the allowance already holds (impairment expense against the allowance, or a release). A
  rejection needs a reason. While a run is open, settings and rates cannot change.
- **The allowance account** is an asset account of its own (a contra asset), and becomes a control
  account: manual journals are refused on it and the database refuses any posting to it that is not
  an approved run. Once runs have posted, it cannot be swapped.
- **The database enforces** (V33): maker-checker; one open run; one posted run per month end, in
  order; each line's ECL is exposure x rate; the run total and count are its lines'; the run's
  entry moves the allowance and expense by exactly the change; after posting, the allowance held
  in every branch equals the run's provision for that branch; nothing is changed or deleted.
- **Reconciliation** check LOAN_LOSS_ALLOWANCE: the ledger balance of the allowance equals the sum
  of approved changes.
- **Report.** Any closed day can be calculated (an estimate between month ends), by stage with
  coverage, alongside the SASRA required allowance (ADR-0030) for comparison; each run's loans can
  be downloaded for the auditors.

## Not built yet (needs decisions)
- Exposure is outstanding principal only; whether to include accrued interest (net of interest in
  suspense) is a finance decision.
- Staging uses days past due only: restructured loans, forward-looking information and
  significant-increase-in-credit-risk indicators other than arrears, and probability-of-default
  models, are not built.
- Off-balance-sheet exposures (undrawn commitments) and the write-off/allowance interaction
  (written-off loans drop out at the next run, releasing their provision) need finance review.
