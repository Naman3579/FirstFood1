package com.firstfood.payment.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.payment.PaymentLedgerView;
import com.firstfood.payment.PaymentService;
import com.firstfood.payment.PaymentView;
import com.firstfood.payment.dto.RejectPaymentRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff side: read a subscription's payments and decide the pending ones. Authorization lives in the service. */
@RestController
@RequestMapping({"/api/v1/providers/{providerId}/subscriptions/{subscriptionId}/payments",
        "/api/v1/staff/providers/{providerId}/subscriptions/{subscriptionId}/payments"})
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public PaymentLedgerView ledger(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        return paymentService.ledger(principal.accountId(), providerId, subscriptionId);
    }

    @PostMapping("/{paymentId}/verify")
    public PaymentView verify(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @PathVariable UUID paymentId) {
        return paymentService.verify(principal.accountId(), providerId, subscriptionId, paymentId);
    }

    @PostMapping("/{paymentId}/reject")
    public PaymentView reject(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody RejectPaymentRequest request) {
        return paymentService.reject(principal.accountId(), providerId, subscriptionId, paymentId, request.reason());
    }
}
