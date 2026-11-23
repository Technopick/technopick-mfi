# ADR-0030: SASRA prudential returns from the ledger and loan book

- Status: Accepted as groundwork (increment 30, 2026-11-23). Compliance sign-off is required before
  any figure is submitted to SASRA.

## Sources (checked 2026-11-23)
- Sacco Societies (Deposit-taking Sacco Business) Regulations, 2010 (LN 95/2010), as revised to
  31 December 2022: Reg 3 (definitions of core and institutional capital), Reg 9 (minimum capital:
  KES 10 million core capital, core capital at least 10% of total assets, institutional capital at
  least 8% of total assets, core capital at least 8% of total deposits), Reg 13 (liquid assets at
  least 15% of savings deposits and short-term liabilities; what counts as liquid), Reg 41(3)
  (performing; watch 1-30 days; substandard 31-180; doubtful 181-360; loss over 360), Reg 42
  (interest on substandard, doubtful and loss loans is suspended), Reg 44(1) (minimum allowance
  1%, 5%, 25%, 50%, 100%). Capital, liquidity and deposit returns are monthly; classification and
  provisioning are quarterly; returns are due by the 15th of the following month.
- SASRA circular, December 2025 (as reported): fair value and credit risk reserves are excluded
  when computing core capital and the capital ratios.
- Sacco Societies (Amendment) Bill, 2025 is before Parliament and may change the framework.

## Decision
- **Settings per tenant** (V27 `ledger.prudential_settings`) default to the regulation figures
  above, and the report says when the defaults are in use. Finance can set stricter figures
  (Reg 10 lets the Authority require more). The database keeps them coherent: bands increase, and
  allowance rates never fall as risk rises.
- **Ledger accounts are mapped to return lines** (V27 `ledger.regulatory_mapping`), and each line
  accepts only accounts of its class (for example, deposits can never be mapped as capital).
  Balance-sheet accounts with a balance that are not mapped are listed; they are never silently
  used. Total assets are all asset accounts, so they always agree with the balance sheet (the API
  test checks this).
- **Figures are computed for a closed business day**, from journal lines up to that date and that
  day's end-of-day arrears snapshots. Each line lists its accounts, and each loan class lists its
  loans. Ratios are judged on the exact fraction, not the rounded percentage. Once the month's
  period is closed the ledger refuses postings to it, so that month's figures are final; until
  then the report is marked provisional.
- **Cross-check:** principal of the loans classified is compared with loan principal in the
  ledger on the same date. Any difference is shown and must be explained before submitting.
- **Interest suspension (Reg 42) is not built.** The report shows how much interest is still
  accrued on non-performing loans so finance can adjust for it.
- **Classification uses days past due only.** Reg 41(3) also allows counting instalments in
  arrears, and restructured loans have their own rule; neither is used (rescheduling is not built).

## Not covered yet (open)
- The exact layout of SASRA's current forms (the Second Schedule forms and SASRA's templates). The
  CSV gives the figures with their sources, not the official form. Deposits return (Form 3),
  investments (Form 5), sectoral returns and the statements of financial position and
  comprehensive income in SASRA's format are not built.
- Decisions for compliance and finance: whether current-year earnings count in core capital (a
  setting, on by default); the base for the allowance (outstanding principal is used now, with no
  offset for member deposits or collateral); which accounts count as short-term liabilities; who
  may change settings and mapping (now one FINANCE user, recorded in the audit trail); and how
  submissions are certified (CEO and finance manager) and archived.
