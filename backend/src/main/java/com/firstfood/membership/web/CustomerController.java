package com.firstfood.membership.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.membership.CustomerView;
import com.firstfood.membership.InvitationView;
import com.firstfood.membership.MembershipService;
import com.firstfood.membership.dto.AddCustomerRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: the acting account is always the JWT principal; every
 * authorization decision happens in {@link MembershipService}.
 */
@RestController
@RequestMapping({"/api/v1/providers/{providerId}/customers", "/api/v1/staff/providers/{providerId}/customers"})
public class CustomerController {

    private final MembershipService membershipService;

    public CustomerController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping
    public List<CustomerView> list(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return membershipService.listCustomers(principal.accountId(), providerId, includeInactive);
    }

    /**
     * Invites a phone number to become a customer. 202, not 201: nothing exists yet - the person must accept. The
     * response is the same whether or not the number has an account.
     */
    @PostMapping
    public ResponseEntity<InvitationView> invite(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody AddCustomerRequest request) {
        InvitationView invitation = membershipService.inviteCustomer(principal.accountId(), providerId, request.phone());
        return ResponseEntity.accepted().body(invitation);
    }

    @GetMapping("/invitations")
    public List<InvitationView> invitations(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        return membershipService.listInvitations(principal.accountId(), providerId);
    }

    @PostMapping("/invitations/{invitationId}/withdraw")
    public InvitationView withdraw(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID invitationId) {
        return membershipService.withdrawInvitation(principal.accountId(), providerId, invitationId);
    }

    @GetMapping("/{membershipId}")
    public CustomerView get(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID membershipId) {
        return membershipService.getCustomer(principal.accountId(), providerId, membershipId);
    }

    @PostMapping("/{membershipId}/deactivate")
    public CustomerView deactivate(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID membershipId) {
        return membershipService.deactivate(principal.accountId(), providerId, membershipId);
    }
}
