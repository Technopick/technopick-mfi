# ADR-0031: Interest in suspense on non-performing loans

- Status: Accepted (increment 31, 2026-11-24). Off until finance switches it on per tenant;
  compliance and finance to confirm the treatment before use.

## Context
Reg 42 of the Sacco Societies (Deposit-taking Sacco Business) Regulations, 2010 suspends interest
on loans classified substandard, doubtful or loss: it is not income until it is received
(ADR-0030 lists the sources). Until now accrued interest on such loans stayed in income, and the
prudential return could only disclose the amount.

## Decision
- **Per-tenant switch and account** (V28, on `ledger.prudential_settings`): finance switches
  suspension on and names an interest-in-suspense account. That account must be an asset account
  (a contra asset, deducted from loans) that is used for nothing else. Once named, it is a control
  account: manual journals are refused on it.
- **The threshold is the classification**: a loan is suspended when it is past the tenant's watch
  band (more than 30 days past due by default), the same rule the prudential return uses.
- **Target balance at each end of day**: after a loan's arrears snapshot, its interest in suspense
  is brought to its unpaid accrued interest if it is non-performing, otherwise to zero. The
  difference is one journal entry between interest income and the suspense account. This one rule
  covers every case. Becoming non-performing moves the interest already accrued and unpaid out of
  income. New accruals follow into suspense the same night. Interest paid on a suspended loan
  becomes income at that day's close. A loan becoming performing again releases its suspense to
  income. Switching the setting off releases everything at the next close. Running it twice
  changes nothing.
- **Closure** releases what is left to income, since everything was paid. **Write-off** releases it
  against the write-off expense, since suspended interest was never income: the expense is the
  amount written off less the suspended interest.
- **Record and checks**: `lending.interest_suspense` keeps one append-only movement per entry. At
  commit the database checks that each movement matches its entry, that each loan's suspense stays
  between zero and its unpaid accrued interest, and that nothing else posts to a suspense account.
  Reconciliation adds `LOAN_INTEREST_SUSPENSE`, comparing the account's balance with the loans'
  movements. The prudential return shows interest in suspense, and any interest on non-performing
  loans still in income.

## Consequences and limits
- Income for a day is recognised at that day's close. Within the day, a payment on a suspended loan
  shows as a lower receivable but its income appears only at the close.
- Penalties on non-performing loans are not suspended (Reg 42 names interest). Whether to suspend
  them too is for compliance.
- Closure release is tested end to end since increment 32 (early settlement of a suspended loan).
- Turning suspension on changes income from the next close onwards; earlier closed periods are not
  restated.
