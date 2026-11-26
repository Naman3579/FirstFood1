package com.firstfood.provideraccess.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.provideraccess.ProviderRoleService;
import com.firstfood.provideraccess.RoleAssignmentView;
import com.firstfood.provideraccess.dto.AssignRoleRequest;
import com.firstfood.provideraccess.OwnershipOfferView;
import com.firstfood.provideraccess.dto.OfferOwnershipRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: the acting account is always the JWT principal; every
 * authorization decision happens in {@link ProviderRoleService}.
 */
@RestController
@RequestMapping({"/api/v1/providers/{providerId}", "/api/v1/staff/providers/{providerId}"})
public class ProviderRoleController {

    private final ProviderRoleService roleService;

    public ProviderRoleController(ProviderRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/roles")
    public List<RoleAssignmentView> list(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        return roleService.findRoles(principal.accountId(), providerId);
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleAssignmentView> assign(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody AssignRoleRequest request) {
        RoleAssignmentView created =
                roleService.assignRole(principal.accountId(), providerId, request.phone(), request.role());
        return ResponseEntity.created(URI.create("/api/v1/providers/" + providerId + "/roles/" + created.id()))
                .body(created);
    }

    @DeleteMapping("/roles/{assignmentId}")
    public RoleAssignmentView revoke(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID assignmentId) {
        return roleService.revokeRole(principal.accountId(), providerId, assignmentId);
    }

    /** Sends a fresh verification code to the OWNER's own phone (needed to offer ownership or close the provider). */
    @PostMapping("/owner-step-up")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestStepUp(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        roleService.requestOwnerStepUp(principal.accountId(), providerId);
    }

    /** Offers ownership; nothing changes hands until the target accepts. */
    @PostMapping("/ownership/offers")
    public ResponseEntity<OwnershipOfferView> offerOwnership(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody OfferOwnershipRequest request) {
        OwnershipOfferView created =
                roleService.offerOwnership(principal.accountId(), providerId, request.phone(), request.code());
        return ResponseEntity.created(URI.create("/api/v1/providers/" + providerId + "/ownership/offers/" + created.id()))
                .body(created);
    }

    @GetMapping("/ownership/offers")
    public List<OwnershipOfferView> listOffers(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        return roleService.listOwnershipOffers(principal.accountId(), providerId);
    }

    @DeleteMapping("/ownership/offers/{offerId}")
    public OwnershipOfferView withdrawOffer(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID offerId) {
        return roleService.withdrawOwnershipOffer(principal.accountId(), providerId, offerId);
    }
}
