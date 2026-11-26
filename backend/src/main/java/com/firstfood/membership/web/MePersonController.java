package com.firstfood.membership.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.membership.MembershipService;
import com.firstfood.membership.MyInvitationView;
import com.firstfood.membership.MyMembershipView;
import com.firstfood.membership.PersonService;
import com.firstfood.membership.PersonView;
import com.firstfood.membership.dto.SavePersonAddressRequest;
import com.firstfood.membership.dto.SavePersonRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own Person profile and memberships. Always scoped to the JWT principal. */
@RestController
@RequestMapping("/api/v1/me")
public class MePersonController {

    private final PersonService personService;
    private final MembershipService membershipService;

    public MePersonController(PersonService personService, MembershipService membershipService) {
        this.personService = personService;
        this.membershipService = membershipService;
    }

    @GetMapping("/person")
    public PersonView getPerson(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return personService.getMyPerson(principal.accountId());
    }

    @PutMapping("/person")
    public PersonView savePerson(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody SavePersonRequest request) {
        return personService.saveMyPerson(principal.accountId(), request.fullName());
    }

    /** Saves the caller's address; an empty body (or a null address) clears it. */
    @PutMapping("/person/address")
    public PersonView saveAddress(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody SavePersonAddressRequest request) {
        return personService.saveMyAddress(principal.accountId(), request.toInput());
    }

    @GetMapping("/memberships")
    public List<MyMembershipView> memberships(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return membershipService.listMine(principal.accountId());
    }

    /** Invitations from providers, addressed to the caller's phone number and still open. */
    @GetMapping("/invitations")
    public List<MyInvitationView> invitations(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return membershipService.listMyInvitations(principal.accountId());
    }

    /** Accepting is what makes the caller a customer and lets the provider see their name, phone and address. */
    @PostMapping("/invitations/{invitationId}/accept")
    public MyMembershipView acceptInvitation(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID invitationId) {
        return membershipService.acceptInvitation(principal.accountId(), invitationId);
    }

    @PostMapping("/invitations/{invitationId}/decline")
    public ResponseEntity<Void> declineInvitation(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID invitationId) {
        membershipService.declineInvitation(principal.accountId(), invitationId);
        return ResponseEntity.noContent().build();
    }

    /** Leaves a provider. The provider stops seeing the caller's phone number and address. */
    @PostMapping("/memberships/{membershipId}/leave")
    public MyMembershipView leave(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID membershipId) {
        return membershipService.leave(principal.accountId(), membershipId);
    }
}
