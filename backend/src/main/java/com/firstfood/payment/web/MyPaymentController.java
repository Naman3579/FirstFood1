package com.firstfood.payment.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.payment.PaymentLedgerView;
import com.firstfood.payment.PaymentService;
import com.firstfood.payment.PaymentView;
import com.firstfood.payment.SubmitPaymentCommand;
import com.firstfood.payment.dto.SubmitPaymentRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own payments on their own subscription. Always scoped to the JWT principal. */
@RestController
@RequestMapping("/api/v1/subscriptions/{subscriptionId}/payments")
public class MyPaymentController {

    private final PaymentService paymentService;

    public MyPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public PaymentLedgerView ledger(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return paymentService.ledgerMine(principal.accountId(), subscriptionId);
    }

    @PostMapping
    public ResponseEntity<PaymentView> submit(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody SubmitPaymentRequest request) {
        PaymentView created = paymentService.submit(principal.accountId(), subscriptionId,
                new SubmitPaymentCommand(request.method(), request.transactionReference(), request.amount(),
                        request.paymentDate(), request.paymentTime(), request.idempotencyKey()));
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + subscriptionId + "/payments"))
                .body(created);
    }
}
