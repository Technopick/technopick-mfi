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
