package ke.co.technopick.mfi.core.lending.domain;

import ke.co.technopick.mfi.canonical.Money;

/**
 * One scheduled installment.
 *
 * @param number         1-based sequence
 * @param principal      principal component
 * @param interest       interest component
 * @param closingBalance outstanding principal after this installment
 */
public record Installment(int number, Money principal, Money interest, Money closingBalance) {

    public Money total() {
        return principal.plus(interest);
    }
}
