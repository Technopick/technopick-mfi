# Loan calculation specification - equal-installment/v1

Status: DRAFT - requires sign-off by a qualified finance professional before production.
Implementation: `services/core-banking/.../lending/domain/ScheduleCalculator.java`
Tests: `ScheduleCalculatorTest` (worked examples + 2,000-case seeded property test).

## Inputs
| Input | Type | Rule |
| --- | --- | --- |
| principal | Money | > 0, currency minor-unit scale |
| periodic rate r | decimal | >= 0; annual rate converted by the product's accrual basis (e.g. 30/360 monthly: annual / 12) |
| periods n | int | 1..600 |
| rounding | mode | product version setting, default HALF_EVEN |

## Reducing balance (annuity)
EMI = round(P * r / (1 - (1 + r)^-n)). For installment k < n: interest_k = round(balance * r),
principal_k = EMI - interest_k. Final installment: principal_n = remaining balance,
interest_n = round(balance * r). If r = 0 the flat method with zero interest is used.

## Flat
Total interest I = round(P * r * n). Non-final installments: principal = floor2(P / n),
interest = floor2(I / n). Final installment takes the remainders (never negative).

## Worked example (fixture)
KES 100,000, 24% p.a., monthly, 12 periods, r = 0.02.

| Method | First installment | Final installment | Total interest |
| --- | --- | --- | --- |
| Reducing balance | 9,455.96 (P 7,455.96 / I 2,000.00) | 9,455.97 (P 9,270.56 / I 185.41) | 13,471.53 |
| Flat | 10,333.33 | 10,333.37 | 24,000.00 |

## Invariants (tested)
1. Sum of principal components = principal, exactly.
2. Closing balance after the final installment = 0.00.
3. No negative component or balance.

## Not yet covered (Phase 3, each as a new method version)
Grace periods (principal and/or interest), moratoria, holidays and business-day shifts,
irregular first period, daily accrual (actual/365), fees and insurance in installments,
rescheduling and early settlement quotations.

## Repayment allocation - allocation/v1
Implementation: `RepaymentAllocator`; tests: `RepaymentAllocatorTest` (worked examples +
3,000-case seeded property test per strategy).

- Installments are paid oldest due date first (ties by installment number).
- Component order is configured per product version; default PENALTY, FEE, INTEREST, PRINCIPAL.
- `COMPONENT_FIRST`: clear each component across all installments before the next component.
- `INSTALLMENT_FIRST`: clear each installment completely before the next installment.
- Any remainder is returned as `unallocated`; the product's overpayment rule (prepay future
  principal or hold as overpayment liability) is applied explicitly by the caller.

Worked example (installments 1 and 2 of the section above overdue, KES 200 penalty on 1):
payment 9,655.96 under COMPONENT_FIRST pays penalty 200.00, interest 2,000.00 (inst 1),
interest 1,850.88 (inst 2), principal 5,605.08 (inst 1). INSTALLMENT_FIRST clears
installment 1 entirely (200.00 + 2,000.00 + 7,455.96). Paying 20,000.00 leaves 888.08 unallocated.

Invariants (tested): allocations + unallocated = payment exactly; no component receives
more than is outstanding; every allocation is positive.

## Interest accrual - accrual/v1
Implementation: `InterestAccrual`; tests: `InterestAccrualTest` (worked example + 2,000-case
property test with skipped EOD days).

- Each installment's scheduled interest accrues daily across its period (previous due date
  exclusive, due date inclusive).
- Accrued-to-date = round(period interest x elapsed days / period days); the day's posting is
  accrued-to-date minus what is already posted. One accrual entry per loan per business date
  (idempotency key `loan:<id>:ACCRUE:<date>`).
- Properties (tested): daily amounts sum exactly to the scheduled interest, are never
  negative, a re-run on the same day posts zero, missed EOD days are caught up.
- Worked example: KES 2,000.00 over 30 days -> 66.67 after day 1, 133.33 after day 2,
  266.67 after day 4, 2,000.00 on the due date.

## Posting rules (LoanPostingRules)
| Event | Debit | Credit |
| --- | --- | --- |
| Disbursement (fee deducted) | Principal receivable (gross) | Channel clearing (net) + Fee income (fee) |
| Daily accrual | Interest receivable | Interest income |
| Penalty / fee charged | Penalty / fee receivable | Penalty / fee income |
| Repayment | Channel clearing (payment) | Receivable per allocated component + Overpayment liability |
| Write-off | Write-off expense | Principal receivable + Interest receivable |

POLICY ITEM FOR FINANCE SIGN-OFF: upfront fees are recognised at disbursement. IFRS 9 would
normally defer fees integral to the effective interest rate; if required, a new product
version maps the fee to a deferred-fee liability and adds an amortisation event.
