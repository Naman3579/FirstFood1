package com.firstfood.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-07: two spellings of one reference must collapse to one canonical value; formats are per method. */
class PaymentReferenceTest {

    @Test
    void spacesSeparatorsAndCaseDoNotMakeADifferentReference() {
        String expected = "123456789012";
        for (String typed : new String[] {"123456789012", "1234 5678 9012", "1234-5678-9012", "1234.5678/9012",
                "1234_5678:9012", "#1234 5678 9012", "1234  5678  9012"}) {
            assertThat(PaymentReference.canonical(typed)).as(typed).isEqualTo(expected);
        }
        assertThat(PaymentReference.canonical("sbin 2310 abc-99xy")).isEqualTo("SBIN2310ABC99XY");
    }

    @Test
    void onlyTheDocumentedCharactersAreAllowedWhenTyping() {
        assertThat(PaymentReference.hasAllowedCharacters("UPI/9812 4091-8231")).isTrue();
        assertThat(PaymentReference.hasAllowedCharacters("<script>")).isFalse();
        assertThat(PaymentReference.hasAllowedCharacters(" 123")).isFalse(); // callers trim first
        assertThat(PaymentReference.hasAllowedCharacters("-123")).isFalse(); // must start with a letter or digit
        assertThat(PaymentReference.hasAllowedCharacters("١٢٣")).isFalse(); // Arabic-Indic digits
        assertThat(PaymentReference.hasAllowedCharacters("1".repeat(151))).isFalse();
    }

    @Test
    void upiNeedsExactlyTwelveDigits() {
        assertThat(PaymentReference.formatProblem(PaymentMethod.UPI, "123456789012")).isEmpty();
        assertThat(PaymentReference.formatProblem(PaymentMethod.UPI, "12345678901")).isPresent();
        assertThat(PaymentReference.formatProblem(PaymentMethod.UPI, "1234567890123")).isPresent();
        assertThat(PaymentReference.formatProblem(PaymentMethod.UPI, "12345678901A")).isPresent();
    }

    @Test
    void bankReferencesAreEightToThirtyFiveLettersAndDigits() {
        assertThat(PaymentReference.formatProblem(PaymentMethod.BANK_TRANSFER, "123456789012")).isEmpty(); // IMPS
        assertThat(PaymentReference.formatProblem(PaymentMethod.BANK_TRANSFER, "SBIN523456789012")).isEmpty(); // NEFT
        assertThat(PaymentReference.formatProblem(PaymentMethod.BANK_TRANSFER, "ABCD1234")).isEmpty();
        assertThat(PaymentReference.formatProblem(PaymentMethod.BANK_TRANSFER, "ABC1234")).isPresent();
        assertThat(PaymentReference.formatProblem(PaymentMethod.BANK_TRANSFER, "A".repeat(36))).isPresent();
    }

    @Test
    void cashAndOtherHaveNoFixedFormatButNeedSomethingToCompare() {
        assertThat(PaymentReference.formatProblem(PaymentMethod.CASH, "R001")).isEmpty();
        assertThat(PaymentReference.formatProblem(PaymentMethod.OTHER, "7")).isEmpty();
        assertThat(PaymentReference.formatProblem(PaymentMethod.CASH, "")).isPresent();
    }
}
