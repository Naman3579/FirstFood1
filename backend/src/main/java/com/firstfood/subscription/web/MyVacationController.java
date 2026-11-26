package com.firstfood.subscription.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.subscription.PauseView;
import com.firstfood.subscription.SubscriptionPauseService;
import com.firstfood.subscription.VacationView;
import com.firstfood.subscription.dto.StartVacationRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's vacation on their OWN subscription. Thin HTTP layer: the account is always the JWT principal and the
 * service resolves the subscription through the caller's own persons, so someone else's id is simply a 404.
 */
@RestController
@RequestMapping("/api/v1/subscriptions/{subscriptionId}/vacation")
public class MyVacationController {

    private final SubscriptionPauseService pauseService;

    public MyVacationController(SubscriptionPauseService pauseService) {
        this.pauseService = pauseService;
    }

    @GetMapping
    public VacationView status(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return pauseService.status(principal.accountId(), subscriptionId);
    }

    /** Starts a vacation. The body is optional: no start date means today. There is no return date. */
    @PostMapping
    public ResponseEntity<PauseView> start(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID subscriptionId,
            @RequestBody(required = false) StartVacationRequest request) {
        PauseView started = pauseService.start(principal.accountId(), subscriptionId,
                request == null ? null : request.startDate());
        return ResponseEntity.status(201).body(started);
    }

    /** Ends the open vacation today (or calls off one that has not begun). Repeating it changes nothing. */
    @PostMapping("/resume")
    public PauseView resume(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return pauseService.resume(principal.accountId(), subscriptionId);
    }
}
