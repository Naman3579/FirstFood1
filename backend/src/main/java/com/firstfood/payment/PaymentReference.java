package com.firstfood.payment;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns what a customer typed as a transaction reference into the one canonical form that is stored, compared and
 * (for UPI / bank transfers) format-checked.
 *
 * <p>The canonical form drops every space and separator and upper-cases, so {@code 1234 5678 9012},
 * {@code 1234-5678-9012} and {@code 123456789012} are the same reference. The database computes the same thing in
 * {@code subscription_payment.reference_key} (V22); keep the two in step - the unique indexes run on that column.
 */
final class PaymentReference {

    /** What the customer may type: letters and digits first, then a few separators. No locale-dependent characters. */
    private static final Pattern TYPED = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 /_.:#-]{0,149}$");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^A-Za-z0-9]");
    /** A UPI UTR / RRN is exactly 12 digits. */
    private static final Pattern UPI_UTR = Pattern.compile("^[0-9]{12}$");
    /** IMPS RRN (12 digits), NEFT UTR (16 characters), RTGS UTR (22 characters) and the odd bank-specific variant. */
    private static final Pattern BANK_REFERENCE = Pattern.compile("^[A-Z0-9]{8,35}$");

    private PaymentReference() {
    }

    /** True when the raw text only uses the characters a reference may contain. */
    static boolean hasAllowedCharacters(String raw) {
        return TYPED.matcher(raw).matches();
    }

    /** Letters and digits only, upper-case: the form two spellings of one reference have in common. */
    static String canonical(String raw) {
        return NOT_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /**
     * The problem with an already-canonical reference for this method, or empty when it is acceptable. Cash and
     * "other" receipt numbers have no fixed format.
     */
    static Optional<String> formatProblem(PaymentMethod method, String canonical) {
        return switch (method) {
            case UPI -> UPI_UTR.matcher(canonical).matches()
                    ? Optional.empty()
                    : Optional.of("A UPI transaction ID (UTR) is exactly 12 digits. Spaces and dashes are fine;"
                            + " letters are not.");
            case BANK_TRANSFER -> BANK_REFERENCE.matcher(canonical).matches()
                    ? Optional.empty()
                    : Optional.of("A bank transfer reference (UTR) is 8 to 35 letters and digits.");
            case CASH, OTHER -> canonical.isEmpty()
                    ? Optional.of("The receipt number needs at least one letter or digit.")
                    : Optional.empty();
        };
    }
}
