package com.firstfood.payment.dto;

import com.firstfood.payment.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A payment the customer says they made. The date and time are their claim (business time zone), the server stamps
 * its own submission time. Which subscription it belongs to comes from the URL, and who submitted it from the JWT -
 * never from this body.
 */
public record SubmitPaymentRequest(
        @NotNull PaymentMethod method,
        @Size(max = 150) String transactionReference,
        @NotNull @DecimalMin(value = "0.01", message = "must be greater than zero")
        @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull LocalDate paymentDate,
        @NotNull LocalTime paymentTime,
        @Size(max = 64) String idempotencyKey) {
}
