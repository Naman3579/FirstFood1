package com.firstfood.provideraccess.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.provideraccess.MyOwnershipOfferView;
import com.firstfood.provideraccess.ProviderRoleService;
import com.firstfood.provideraccess.RoleAssignmentView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The receiving side of an ownership offer (F-08): the signed-in staff account sees offers addressed to its own phone
 * and accepts or declines them. Lives under /api/v1/staff, so the staff-portal check applies.
 */
@RestController
@RequestMapping("/api/v1/staff/ownership-offers")
public class MyOwnershipOfferController {

    private final ProviderRoleService roleService;

    public MyOwnershipOfferController(ProviderRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public List<MyOwnershipOfferView> mine(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return roleService.listMyOwnershipOffers(principal.accountId());
    }

    /** Returns [new owner (you), former owner now MANAGER]. */
    @PostMapping("/{offerId}/accept")
    public List<RoleAssignmentView> accept(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID offerId) {
        return roleService.acceptOwnershipOffer(principal.accountId(), offerId);
    }

    @PostMapping("/{offerId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID offerId) {
        roleService.declineOwnershipOffer(principal.accountId(), offerId);
    }
}
