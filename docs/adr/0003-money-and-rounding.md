# ADR-0003: Money representation and rounding

- Status: Accepted (subject to finance sign-off of calculation specs)
- Date: 2026-11-18

## Decision
- Java: `ke.co.technopick.mfi.canonical.Money` wraps `BigDecimal` at the currency's
  minor-unit scale (KES = 2). No `float`/`double` anywhere in money code (ArchUnit rule).
- Database: `numeric(19,4)` for amounts, `numeric(12,8)` for rates.
- Exact construction rejects sub-minor-unit amounts; rounding must be explicit.
- Default rounding `HALF_EVEN`; a product version may specify another mode.
- Intermediate calculation uses `MathContext.DECIMAL128`.
- Schedules: equal installments; for flat-rate splits, non-final installments are rounded
  `DOWN` and the final installment absorbs the non-negative remainder, so components always
  sum exactly to principal and total interest.
- Every calculation method carries a version (`ScheduleCalculator.METHOD_VERSION`); loans
  pin the method version and product version they were created with.

## Verified
Worked examples (KES 100,000, 24% p.a., 12 months) and a 2,000-case seeded property test
pass - see `ScheduleCalculatorTest`.
