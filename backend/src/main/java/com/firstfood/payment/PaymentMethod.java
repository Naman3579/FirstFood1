package com.firstfood.payment;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentMethod {
    UPI,
    BANK_TRANSFER,
    CASH,
    OTHER;

    /** UPI and bank transfers leave a reference that can be checked, so one is mandatory; cash may not have one. */
    public boolean requiresReference() {
        return this == UPI || this == BANK_TRANSFER;
    }

    /** The methods whose references identify one real transfer, whichever of them the customer picked. */
    public static final Set<PaymentMethod> DIGITAL = EnumSet.of(UPI, BANK_TRANSFER);

    /** Digital references are unique system-wide; cash/other receipt numbers only within one provider. */
    public boolean referenceUniqueAcrossProviders() {
        return requiresReference();
    }
}
