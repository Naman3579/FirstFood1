package com.firstfood.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 5 HTTP-level tests: Person + ProviderMembership. Real Postgres + Redis, real
 * OTP login. Phones use +91987654xxxx (earlier phases use ...650xxxx to ...653xxxx);
 * the database is a shared singleton container, so do not reuse.
 */
@TestPropertySource(properties = "app.membership.max-invitations-per-provider-per-day=3")
class MembershipApiIntegrationTest extends AbstractIntegrationTest {

    /** Accounts logged in by this test, by phone, so the invite-and-accept helper can act as the customer. */
    private final Map<String, Account> accounts = new HashMap<>();

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------ authentication

    @Test
    void membershipEndpointsRequireAuthentication() {
        UUID anyId = UUID.randomUUID();
        for (String[] call : new String[][] {
                {"GET", "/api/v1/providers/" + anyId + "/customers"},
                {"POST", "/api/v1/providers/" + anyId + "/customers"},
                {"GET", "/api/v1/providers/" + anyId + "/customers/" + anyId},
                {"POST", "/api/v1/providers/" + anyId + "/customers/" + anyId + "/deactivate"},
                {"GET", "/api/v1/me/person"},
                {"PUT", "/api/v1/me/person"},
                {"GET", "/api/v1/me/memberships"},
                {"GET", "/api/v1/me/invitations"},
                {"POST", "/api/v1/me/invitations/" + anyId + "/accept"},
                {"POST", "/api/v1/me/invitations/" + anyId + "/decline"},
                {"POST", "/api/v1/me/memberships/" + anyId + "/leave"},
                {"GET", "/api/v1/providers/" + anyId + "/customers/invitations"},
                {"POST", "/api/v1/providers/" + anyId + "/customers/invitations/" + anyId + "/withdraw"}}) {
            Object body = call[0].equals("GET") ? null : Map.of();
            assertThat(statusOf(send(call[0], call[1], null, body))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ add / list / get

    @Test
    void ownerInvitesByPhoneAndSeesTheCustomerOnlyOnceTheyAccept() {
        Account owner = login("+919876540001");
        Account customer = login("+919876540002");
        UUID provider = createProvider(owner, "Notebook Mess");

        CustomerView added = add(owner, provider, "+919876540002", "Asha Verma");
        assertThat(added.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(added.fullName()).isEqualTo("Asha Verma");
        assertThat(added.phone()).isEqualTo("+919876540002");
        assertThat(added.providerId()).isEqualTo(provider);
        assertThat(added.leftAt()).isNull();
        // The actor is taken from the JWT, never from the request.
        assertThat(added.addedBy()).isEqualTo(owner.id());

        List<CustomerView> list = listCustomers(owner, provider, false);
        assertThat(list).extracting(CustomerView::membershipId).containsExactly(added.membershipId());

        CustomerView fetched = send("GET", "/api/v1/providers/" + provider + "/customers/" + added.membershipId(),
                owner.accessToken(), null).expectStatus().isOk().expectBody(CustomerView.class).returnResult()
                .getResponseBody();
        assertThat(fetched.personId()).isEqualTo(added.personId());

        // The customer sees the same person + membership from their own side.
        PersonView mine = send("GET", "/api/v1/me/person", customer.accessToken(), null)
                .expectStatus().isOk().expectBody(PersonView.class).returnResult().getResponseBody();
        assertThat(mine.id()).isEqualTo(added.personId());
        assertThat(mine.primaryPerson()).isTrue();
        List<MyMembershipView> memberships = myMemberships(customer);
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).providerName()).isEqualTo("Notebook Mess");
        assertThat(memberships.get(0).status()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void addingTheSameActiveCustomerTwiceIsRejectedAndLeavesOneRow() {
        Account owner = login("+919876540003");
        login("+919876540004");
        UUID provider = createProvider(owner, "Dup Mess");
        add(owner, provider, "+919876540004", "Ravi");

        assertError(sendAdd(owner, provider, "+919876540004", "Ravi"), HttpStatus.CONFLICT,
                "MEMBERSHIP_ALREADY_ACTIVE");
        assertThat(membershipRows(provider)).isEqualTo(1);
    }

    @Test
    void invitingARegisteredAndAnUnregisteredNumberLooksIdentical() {
        Account owner = login("+919876540005");
        login("+919876540040"); // registered
        UUID provider = createProvider(owner, "Oracle Mess");

        // F-03: the answer must not reveal whether a number has an account.
        Map<?, ?> registered = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", "+919876540040")).expectStatus().isAccepted().expectBody(Map.class).returnResult()
                .getResponseBody();
        Map<?, ?> unregistered = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", "+919876549999")).expectStatus().isAccepted().expectBody(Map.class).returnResult()
                .getResponseBody();

        assertThat(registered.keySet()).isEqualTo(unregistered.keySet());
        assertThat(registered.get("status")).isEqualTo("PENDING").isEqualTo(unregistered.get("status"));
        assertThat(registered.get("providerId")).isEqualTo(unregistered.get("providerId"));
        // Nothing but invitations exists: no membership and no profile was created for anyone.
        assertThat(membershipRows(provider)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from membership_invitation where provider_id = ?",
                Integer.class, provider)).isEqualTo(2);
        // The provider's own list cannot tell them apart either.
        assertThat(providerInvitations(owner, provider)).extracting(InvitationView::status)
                .containsOnly(InvitationStatus.PENDING);
        assertThat(listCustomers(owner, provider, true)).isEmpty();

        // A malformed number is knowable without any lookup, so it is simply a validation error.
        assertError(sendAdd(owner, provider, "not-a-phone", null), HttpStatus.BAD_REQUEST, "INVALID_PHONE");
    }

    @Test
    void invitingWithoutAcceptanceReleasesNothingAndCreatesNoProfile() {
        Account owner = login("+919876540041");
        Account customer = login("+919876540042");
        UUID provider = createProvider(owner, "Consent Mess");
        savePerson(customer, "Private Person");
        send("PUT", "/api/v1/me/person/address", customer.accessToken(), Map.of("addressLine", "7 Secret Lane",
                "locality", "Arera Colony", "city", "Bhopal")).expectStatus().isOk();

        InvitationView invitation = invite(owner, provider, "+919876540042");

        // The provider has no membership, so no name, phone or address; the customer sees an invitation, not a membership.
        assertThat(listCustomers(owner, provider, true)).isEmpty();
        assertThat(myMemberships(customer)).isEmpty();
        List<MyInvitationView> mine = myInvitations(customer);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).providerName()).isEqualTo("Consent Mess");
        assertThat(mine.get(0).invitationId()).isEqualTo(invitation.invitationId());

        // Only after accepting does the provider see them - and then phone and address are released.
        MyMembershipView accepted = accept(customer, invitation.invitationId());
        CustomerView seen = listCustomers(owner, provider, false).get(0);
        assertThat(seen.membershipId()).isEqualTo(accepted.membershipId());
        assertThat(seen.fullName()).isEqualTo("Private Person");
        assertThat(seen.phone()).isEqualTo("+919876540042");
        assertThat(seen.address().addressLine()).isEqualTo("7 Secret Lane");
        assertThat(myInvitations(customer)).isEmpty();
    }

    @Test
    void aProviderNeverSuppliesAProfileNameTheCustomerDoes() {
        Account owner = login("+919876540006");
        Account customer = login("+919876540007");
        UUID provider = createProvider(owner, "Name Mess");

        // The staff-supplied fullName is ignored (F-03): it can no longer become someone's profile name.
        InvitationView invitation = sendAdd(owner, provider, "+919876540007", "Name Chosen By Staff")
                .expectStatus().isAccepted().expectBody(InvitationView.class).returnResult().getResponseBody();
        assertThat(personRows(customer.id())).isZero();

        // Accepting needs the customer's OWN name; until then the invitation stays open and nothing is created.
        assertError(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                customer.accessToken(), Map.of()), HttpStatus.BAD_REQUEST, "PERSON_NAME_REQUIRED");
        assertThat(personRows(customer.id())).isZero();
        assertThat(membershipRows(provider)).isZero();
        assertThat(invitationStatusInDb(invitation.invitationId())).isEqualTo("PENDING");

        savePerson(customer, "  Meera Shah  ");
        MyMembershipView accepted = accept(customer, invitation.invitationId());
        assertThat(accepted.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(listCustomers(owner, provider, false).get(0).fullName()).isEqualTo("Meera Shah");

        // A second provider cannot rename the existing person either.
        Account owner2 = login("+919876540008");
        UUID provider2 = createProvider(owner2, "Other Mess");
        CustomerView addedElsewhere = add(owner2, provider2, "+919876540007", "Totally Different Name");
        assertThat(addedElsewhere.fullName()).isEqualTo("Meera Shah");
        assertThat(personRows(customer.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ invitation lifecycle

    @Test
    void reInvitingTheSameNumberReturnsTheOpenInvitation() {
        Account owner = login("+919876540043");
        UUID provider = createProvider(owner, "Idempotent Mess");

        InvitationView first = invite(owner, provider, "+919876540044");
        InvitationView again = invite(owner, provider, "+91 98765-40044"); // same number, different spelling
        InvitationView plus = invite(owner, provider, "919876540044");     // and without the '+'

        assertThat(again.invitationId()).isEqualTo(first.invitationId());
        assertThat(plus.invitationId()).isEqualTo(first.invitationId());
        assertThat(providerInvitations(owner, provider)).hasSize(1);
    }

    @Test
    void anInvitationAddressedToSomeoneElseCannotBeSeenOrAnswered() {
        Account owner = login("+919876540045");
        Account invited = login("+919876540046");
        Account stranger = login("+919876540047");
        UUID provider = createProvider(owner, "Private Invite Mess");
        savePerson(stranger, "Stranger");
        InvitationView invitation = invite(owner, provider, "+919876540046");

        assertThat(myInvitations(stranger)).isEmpty();
        assertError(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                stranger.accessToken(), Map.of()), HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        assertError(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/decline",
                stranger.accessToken(), Map.of()), HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        assertThat(invitationStatusInDb(invitation.invitationId())).isEqualTo("PENDING");
        assertThat(invited.id()).isNotNull();
    }

    @Test
    void decliningCreatesNothingAndALaterInvitationIsNew() {
        Account owner = login("+919876540048");
        Account customer = login("+919876540049");
        UUID provider = createProvider(owner, "Decline Mess");
        InvitationView first = invite(owner, provider, "+919876540049");

        send("POST", "/api/v1/me/invitations/" + first.invitationId() + "/decline", customer.accessToken(), Map.of())
                .expectStatus().isNoContent();

        assertThat(invitationStatusInDb(first.invitationId())).isEqualTo("DECLINED");
        assertThat(membershipRows(provider)).isZero();
        assertThat(myInvitations(customer)).isEmpty();
        // Answering twice is refused.
        assertError(send("POST", "/api/v1/me/invitations/" + first.invitationId() + "/decline",
                customer.accessToken(), Map.of()), HttpStatus.CONFLICT, "INVITATION_NOT_PENDING");
        // The provider may ask again; that is a fresh invitation.
        InvitationView second = invite(owner, provider, "+919876540049");
        assertThat(second.invitationId()).isNotEqualTo(first.invitationId());
        assertThat(second.status()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void aWithdrawnInvitationCanNoLongerBeAccepted() {
        Account owner = login("+919876540050");
        Account customer = login("+919876540051");
        UUID provider = createProvider(owner, "Withdraw Mess");
        savePerson(customer, "Late Accepter");
        InvitationView invitation = invite(owner, provider, "+919876540051");

        InvitationView withdrawn = send("POST", "/api/v1/providers/" + provider + "/customers/invitations/"
                + invitation.invitationId() + "/withdraw", owner.accessToken(), Map.of())
                .expectStatus().isOk().expectBody(InvitationView.class).returnResult().getResponseBody();
        assertThat(withdrawn.status()).isEqualTo(InvitationStatus.WITHDRAWN);

        assertThat(myInvitations(customer)).isEmpty();
        assertError(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                customer.accessToken(), Map.of()), HttpStatus.CONFLICT, "INVITATION_NOT_PENDING");
        assertThat(membershipRows(provider)).isZero();
        // Another provider's invitation id is not resolvable through this provider's path.
        Account other = login("+919876540052");
        UUID otherProvider = createProvider(other, "Other Withdraw Mess");
        assertError(send("POST", "/api/v1/providers/" + otherProvider + "/customers/invitations/"
                + invitation.invitationId() + "/withdraw", other.accessToken(), Map.of()),
                HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
    }

    @Test
    void anExpiredInvitationCannotBeAcceptedAndTheNumberCanBeInvitedAgain() {
        Account owner = login("+919876540053");
        Account customer = login("+919876540054");
        UUID provider = createProvider(owner, "Expiry Mess");
        savePerson(customer, "Too Slow");
        InvitationView old = invite(owner, provider, "+919876540054");
        jdbc.update("update membership_invitation set created_at = now() - interval '10 days', "
                + "expires_at = now() - interval '1 second' where id = ?", old.invitationId());

        assertThat(myInvitations(customer)).isEmpty();
        assertThat(providerInvitations(owner, provider).get(0).status()).isEqualTo(InvitationStatus.EXPIRED);
        assertError(send("POST", "/api/v1/me/invitations/" + old.invitationId() + "/accept", customer.accessToken(),
                Map.of()), HttpStatus.GONE, "INVITATION_EXPIRED");
        assertThat(invitationStatusInDb(old.invitationId())).isEqualTo("EXPIRED"); // the lapse was persisted
        assertThat(membershipRows(provider)).isZero();

        InvitationView fresh = invite(owner, provider, "+919876540054");
        assertThat(fresh.invitationId()).isNotEqualTo(old.invitationId());
        assertThat(accept(customer, fresh.invitationId()).status()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void invitationsAreBudgetedPerProviderPerDay() {
        Account owner = login("+919876540055");
        UUID provider = createProvider(owner, "Budget Mess");

        // app.membership.max-invitations-per-provider-per-day = 3 in this test class. Numbers need no account.
        invite(owner, provider, "+919876540101");
        invite(owner, provider, "+919876540102");
        invite(owner, provider, "+919876540103");
        invite(owner, provider, "+919876540101"); // a repeat is not a new invitation and costs nothing

        assertError(sendAdd(owner, provider, "+919876540104", null), HttpStatus.TOO_MANY_REQUESTS,
                "INVITATION_LIMIT_REACHED");
        assertThat(providerInvitations(owner, provider)).hasSize(3);
    }

    // ------------------------------------------------------------ customer leaves

    @Test
    void aCustomerCanLeaveAndTheProviderLosesTheirPhoneAndAddress() {
        Account owner = login("+919876540056");
        Account customer = login("+919876540057");
        UUID provider = createProvider(owner, "Leave Mess");
        savePerson(customer, "Leaver");
        send("PUT", "/api/v1/me/person/address", customer.accessToken(), Map.of("addressLine", "9 Home Road",
                "locality", "Kolar", "city", "Bhopal")).expectStatus().isOk();
        CustomerView member = add(owner, provider, "+919876540057", null);
        assertThat(member.phone()).isNotNull();
        assertThat(member.address()).isNotNull();

        MyMembershipView left = send("POST", "/api/v1/me/memberships/" + member.membershipId() + "/leave",
                customer.accessToken(), Map.of()).expectStatus().isOk().expectBody(MyMembershipView.class)
                .returnResult().getResponseBody();
        assertThat(left.status()).isEqualTo(MembershipStatus.INACTIVE);
        assertThat(left.leftAt()).isNotNull();

        // The provider keeps the history by name, but no longer sees how to reach or find the person.
        assertThat(listCustomers(owner, provider, false)).isEmpty();
        CustomerView history = listCustomers(owner, provider, true).get(0);
        assertThat(history.status()).isEqualTo(MembershipStatus.INACTIVE);
        assertThat(history.fullName()).isEqualTo("Leaver");
        assertThat(history.phone()).isNull();
        assertThat(history.address()).isNull();
        assertThat(history.leftBy()).isEqualTo(customer.id());

        assertError(send("POST", "/api/v1/me/memberships/" + member.membershipId() + "/leave",
                customer.accessToken(), Map.of()), HttpStatus.CONFLICT, "MEMBERSHIP_ALREADY_INACTIVE");
    }

    @Test
    void aCustomerCannotLeaveSomeoneElsesMembership() {
        Account owner = login("+919876540058");
        login("+919876540059");
        Account stranger = login("+919876540060");
        UUID provider = createProvider(owner, "No Hijack Mess");
        CustomerView member = add(owner, provider, "+919876540059", "Member");
        savePerson(stranger, "Stranger Danger");

        assertError(send("POST", "/api/v1/me/memberships/" + member.membershipId() + "/leave",
                stranger.accessToken(), Map.of()), HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        assertThat(listCustomers(owner, provider, false)).hasSize(1);
    }

    @Test
    void aCustomerMayLeaveEvenAfterTheProviderClosed() {
        Account owner = login("+919876540061");
        Account customer = login("+919876540062");
        UUID provider = createProvider(owner, "Closed Leave Mess");
        CustomerView member = add(owner, provider, "+919876540062", "Stuck");
        send("PATCH", "/api/v1/providers/" + provider, owner.accessToken(),
                closeBody(owner.accessToken(), provider, null))
                .expectStatus().isOk();

        send("POST", "/api/v1/me/memberships/" + member.membershipId() + "/leave", customer.accessToken(), Map.of())
                .expectStatus().isOk();
    }

    // ------------------------------------------------------------ leave / rejoin / history

    @Test
    void deactivatingKeepsHistoryAndRejoiningCreatesANewStint() {
        Account owner = login("+919876540009");
        Account customer = login("+919876540010");
        UUID provider = createProvider(owner, "History Mess");
        CustomerView first = add(owner, provider, "+919876540010", "Kabir");

        CustomerView left = send("POST", "/api/v1/providers/" + provider + "/customers/" + first.membershipId()
                + "/deactivate", owner.accessToken(), null).expectStatus().isOk()
                .expectBody(CustomerView.class).returnResult().getResponseBody();
        assertThat(left.status()).isEqualTo(MembershipStatus.INACTIVE);
        assertThat(left.leftAt()).isNotNull();
        assertThat(left.leftBy()).isEqualTo(owner.id());

        assertThat(listCustomers(owner, provider, false)).isEmpty();
        assertThat(listCustomers(owner, provider, true)).extracting(CustomerView::membershipId)
                .containsExactly(first.membershipId());
        // Ending the membership takes the contact details away from the provider as well.
        assertThat(listCustomers(owner, provider, true).get(0).phone()).isNull();

        assertError(send("POST", "/api/v1/providers/" + provider + "/customers/" + first.membershipId()
                + "/deactivate", owner.accessToken(), null), HttpStatus.CONFLICT, "MEMBERSHIP_ALREADY_INACTIVE");

        // Rejoin: a NEW membership row; the earlier stint is untouched.
        CustomerView second = add(owner, provider, "+919876540010", null);
        assertThat(second.membershipId()).isNotEqualTo(first.membershipId());
        assertThat(second.personId()).isEqualTo(first.personId());
        assertThat(second.status()).isEqualTo(MembershipStatus.ACTIVE);

        List<CustomerView> all = listCustomers(owner, provider, true);
        assertThat(all).hasSize(2);
        CustomerView oldStint = all.stream().filter(c -> c.membershipId().equals(first.membershipId())).findFirst()
                .orElseThrow();
        assertThat(oldStint.status()).isEqualTo(MembershipStatus.INACTIVE);
        assertThat(oldStint.leftAt().truncatedTo(ChronoUnit.MICROS)).isEqualTo(left.leftAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(listCustomers(owner, provider, false)).extracting(CustomerView::membershipId)
                .containsExactly(second.membershipId());
        assertThat(myMemberships(customer)).hasSize(2);
    }

    @Test
    void sameCustomerCanBeActiveAtSeveralProvidersAtOnce() {
        Account ownerA = login("+919876540011");
        Account ownerB = login("+919876540012");
        Account customer = login("+919876540013");
        UUID providerA = createProvider(ownerA, "Mess A");
        UUID providerB = createProvider(ownerB, "Mess B");

        add(ownerA, providerA, "+919876540013", "Dev");
        add(ownerB, providerB, "+919876540013", null);

        assertThat(myMemberships(customer)).extracting(MyMembershipView::providerName)
                .containsExactlyInAnyOrder("Mess A", "Mess B");
        assertThat(personRows(customer.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ authorization matrix + isolation

    @Test
    void managerMayManageWorkerMayOnlyViewAndOutsidersGetNotFound() {
        Account owner = login("+919876540014");
        Account manager = login("+919876540015");
        Account worker = login("+919876540016");
        Account outsider = login("+919876540017");
        login("+919876540018");
        UUID provider = createProvider(owner, "Matrix Mess");
        assignRole(owner, provider, "+919876540015", "MANAGER");
        assignRole(owner, provider, "+919876540016", "WORKER");

        // MANAGER: add + deactivate.
        CustomerView added = add(manager, provider, "+919876540018", "Via Manager");
        assertThat(added.addedBy()).isEqualTo(manager.id());

        // WORKER: may view, may not mutate.
        assertThat(listCustomers(worker, provider, false)).hasSize(1);
        send("GET", "/api/v1/providers/" + provider + "/customers/" + added.membershipId(), worker.accessToken(),
                null).expectStatus().isOk();
        assertError(sendAdd(worker, provider, "+919876540018", "x"), HttpStatus.FORBIDDEN,
                "INSUFFICIENT_PERMISSION");
        assertError(send("POST", "/api/v1/providers/" + provider + "/customers/" + added.membershipId()
                + "/deactivate", worker.accessToken(), null), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");

        // Outsider (no role): provider existence is not revealed.
        assertError(send("GET", "/api/v1/providers/" + provider + "/customers", outsider.accessToken(), null),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertError(sendAdd(outsider, provider, "+919876540018", "x"), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");

        // Manager can end it.
        send("POST", "/api/v1/providers/" + provider + "/customers/" + added.membershipId() + "/deactivate",
                manager.accessToken(), null).expectStatus().isOk();
    }

    @Test
    void providersAreIsolatedFromEachOther() {
        Account ownerA = login("+919876540019");
        Account ownerB = login("+919876540020");
        login("+919876540021");
        UUID providerA = createProvider(ownerA, "Isolated A");
        UUID providerB = createProvider(ownerB, "Isolated B");
        CustomerView inA = add(ownerA, providerA, "+919876540021", "Only In A");

        // B's owner sees nothing of A's customers...
        assertThat(listCustomers(ownerB, providerB, true)).isEmpty();
        assertError(send("GET", "/api/v1/providers/" + providerA + "/customers", ownerB.accessToken(), null),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        // ...and cannot reach A's membership id through B's own provider path (no IDOR).
        assertError(send("GET", "/api/v1/providers/" + providerB + "/customers/" + inA.membershipId(),
                ownerB.accessToken(), null), HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        assertError(send("POST", "/api/v1/providers/" + providerB + "/customers/" + inA.membershipId()
                + "/deactivate", ownerB.accessToken(), null), HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        assertThat(listCustomers(ownerA, providerA, false)).hasSize(1);
    }

    // ------------------------------------------------------------ provider state rules

    @Test
    void providerNotAcceptingCustomersBlocksAddsButNotViewOrDeactivate() {
        Account owner = login("+919876540022");
        login("+919876540023");
        login("+919876540024");
        UUID provider = createProvider(owner, "Paused Mess");
        CustomerView existing = add(owner, provider, "+919876540023", "Existing");

        send("PATCH", "/api/v1/providers/" + provider, owner.accessToken(), Map.of("acceptingNewCustomers", false))
                .expectStatus().isOk();

        assertError(sendAdd(owner, provider, "+919876540024", "New"), HttpStatus.CONFLICT,
                "PROVIDER_NOT_ACCEPTING_CUSTOMERS");
        assertThat(listCustomers(owner, provider, false)).hasSize(1);
        send("POST", "/api/v1/providers/" + provider + "/customers/" + existing.membershipId() + "/deactivate",
                owner.accessToken(), null).expectStatus().isOk();
    }

    @Test
    void closedProviderIsReadOnlyButKeepsItsCustomerHistory() {
        Account owner = login("+919876540025");
        login("+919876540026");
        login("+919876540027");
        UUID provider = createProvider(owner, "Closing Mess");
        CustomerView existing = add(owner, provider, "+919876540026", "Stays Listed");

        send("PATCH", "/api/v1/providers/" + provider, owner.accessToken(),
                closeBody(owner.accessToken(), provider, null))
                .expectStatus().isOk();

        assertError(sendAdd(owner, provider, "+919876540027", "Late"), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(send("POST", "/api/v1/providers/" + provider + "/customers/" + existing.membershipId()
                + "/deactivate", owner.accessToken(), null), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        // History stays readable.
        assertThat(listCustomers(owner, provider, true)).extracting(CustomerView::membershipId)
                .containsExactly(existing.membershipId());
    }

    // ------------------------------------------------------------ /me/person

    @Test
    void customerCanCreateAndRenameOwnPerson() {
        Account account = login("+919876540028");
        assertError(send("GET", "/api/v1/me/person", account.accessToken(), null), HttpStatus.NOT_FOUND,
                "PERSON_NOT_FOUND");
        assertThat(myMemberships(account)).isEmpty();

        PersonView created = savePerson(account, "Nisha Rao");
        assertThat(created.fullName()).isEqualTo("Nisha Rao");
        assertThat(created.primaryPerson()).isTrue();

        PersonView renamed = savePerson(account, "Nisha R. Rao");
        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(renamed.fullName()).isEqualTo("Nisha R. Rao");
        assertThat(personRows(account.id())).isEqualTo(1);

        assertThat(statusOf(send("PUT", "/api/v1/me/person", account.accessToken(), Map.of("fullName", "   "))))
                .isEqualTo(400);
    }

    @Test
    void personCreatedByCustomerIsReusedWhenAProviderAddsThem() {
        Account owner = login("+919876540029");
        Account customer = login("+919876540030");
        UUID provider = createProvider(owner, "Reuse Mess");
        PersonView own = savePerson(customer, "Self Named");

        CustomerView added = add(owner, provider, "+919876540030", null);
        assertThat(added.personId()).isEqualTo(own.id());
        assertThat(added.fullName()).isEqualTo("Self Named");
    }

    // ------------------------------------------------------------ database-level guarantees

    @Test
    void oneAccountCanOwnSeveralPersonsButOnlyOnePrimary() {
        Account account = login("+919876540031");
        savePerson(account, "Primary Self");

        // Rule 3.2: a second (non-primary) person for the same account must be possible.
        jdbc.update("insert into person (user_account_id, full_name, is_primary) values (?, 'Family Member', false)",
                account.id());
        assertThat(personRows(account.id())).isEqualTo(2);

        // ...but a second PRIMARY person is rejected by the database.
        assertThatThrownBy(() -> jdbc.update(
                "insert into person (user_account_id, full_name, is_primary) values (?, 'Second Self', true)",
                account.id())).isInstanceOf(DataIntegrityViolationException.class);

        // /me/memberships covers every person of the account.
        UUID familyMember = jdbc.queryForObject(
                "select id from person where user_account_id = ? and not is_primary", UUID.class, account.id());
        Account owner = login("+919876540032");
        UUID provider = createProvider(owner, "Family Mess");
        jdbc.update("insert into provider_membership (provider_id, person_id, added_by) values (?, ?, ?)", provider,
                familyMember, owner.id());
        assertThat(myMemberships(account)).extracting(MyMembershipView::personId).containsExactly(familyMember);
    }

    @Test
    void databaseRejectsDuplicateActiveMembershipAndInconsistentLifecycle() {
        Account owner = login("+919876540033");
        Account customer = login("+919876540034");
        UUID provider = createProvider(owner, "Constraint Mess");
        CustomerView added = add(owner, provider, "+919876540034", "Constrained");

        assertThatThrownBy(() -> jdbc.update(
                "insert into provider_membership (provider_id, person_id, added_by) values (?, ?, ?)", provider,
                added.personId(), owner.id())).isInstanceOf(DataIntegrityViolationException.class);

        // INACTIVE without departure metadata, and ACTIVE with it, are both impossible.
        assertThatThrownBy(() -> jdbc.update("update provider_membership set status = 'INACTIVE' where id = ?",
                added.membershipId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update provider_membership set left_at = now() where id = ?",
                added.membershipId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(customer.id()).isNotNull();
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentInvitesOfTheSameNumberCreateExactlyOnePendingInvitation() throws Exception {
        Account owner = login("+919876540035");
        login("+919876540036");
        UUID provider = createProvider(owner, "Race Mess");

        List<Integer> statuses = runConcurrently(
                () -> statusOf(sendAdd(owner, provider, "+919876540036", null)),
                () -> statusOf(sendAdd(owner, provider, "+919876540036", null)),
                () -> statusOf(sendAdd(owner, provider, "+919876540036", null)),
                () -> statusOf(sendAdd(owner, provider, "+919876540036", null)));

        assertThat(statuses).containsOnly(202);
        assertThat(jdbc.queryForObject(
                "select count(*) from membership_invitation where provider_id = ? and status = 'PENDING'",
                Integer.class, provider)).isEqualTo(1);
        assertThat(membershipRows(provider)).isZero();
    }

    @Test
    void concurrentAcceptsOfOneInvitationCreateExactlyOneMembership() throws Exception {
        Account owner = login("+919876540063");
        Account customer = login("+919876540064");
        UUID provider = createProvider(owner, "Accept Race Mess");
        savePerson(customer, "Racer");
        InvitationView invitation = invite(owner, provider, "+919876540064");

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                        customer.accessToken(), Map.of())),
                () -> statusOf(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                        customer.accessToken(), Map.of())),
                () -> statusOf(send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                        customer.accessToken(), Map.of())));

        assertThat(statuses.stream().filter(st -> st == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(st -> st == 409).count()).isEqualTo(2);
        assertThat(membershipRows(provider)).isEqualTo(1);
    }

    @Test
    void twoProvidersInvitingTheSameNewCustomerShareOnePersonOnceAccepted() throws Exception {
        Account ownerA = login("+919876540037");
        Account ownerB = login("+919876540038");
        Account customer = login("+919876540039");
        UUID providerA = createProvider(ownerA, "Race A");
        UUID providerB = createProvider(ownerB, "Race B");
        savePerson(customer, "Shared Person");
        InvitationView inviteA = invite(ownerA, providerA, "+919876540039");
        InvitationView inviteB = invite(ownerB, providerB, "+919876540039");

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", "/api/v1/me/invitations/" + inviteA.invitationId() + "/accept",
                        customer.accessToken(), Map.of())),
                () -> statusOf(send("POST", "/api/v1/me/invitations/" + inviteB.invitationId() + "/accept",
                        customer.accessToken(), Map.of())));

        assertThat(statuses).containsExactly(200, 200);
        assertThat(personRows(customer.id())).isEqualTo(1);
        assertThat(myMemberships(customer)).hasSize(2);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String accessToken) {
    }

    private Account login(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        AuthTokensResponse tokens = restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        ProfileResponse profile = restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
        Account account = new Account(profile.accountId(), tokens.accessToken());
        accounts.put(phone, account);
        return account;
    }

    private UUID createProvider(Account owner, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    private void assignRole(Account owner, UUID providerId, String phone, String role) {
        send("POST", "/api/v1/providers/" + providerId + "/roles", owner.accessToken(),
                Map.of("phone", phone, "role", role)).expectStatus().isCreated();
    }

    /** token == null sends no Authorization header. */
    private RestTestClient.ResponseSpec send(String method, String uri, String token, Object body) {
        RestTestClient.RequestBodyUriSpec spec = switch (method) {
            case "GET" -> restClient.method(org.springframework.http.HttpMethod.GET);
            case "POST" -> restClient.method(org.springframework.http.HttpMethod.POST);
            case "PUT" -> restClient.method(org.springframework.http.HttpMethod.PUT);
            case "PATCH" -> restClient.method(org.springframework.http.HttpMethod.PATCH);
            default -> throw new IllegalArgumentException(method);
        };
        RestTestClient.RequestBodySpec request = spec.uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            return request.body(body).exchange();
        }
        return request.exchange();
    }

    private RestTestClient.ResponseSpec sendAdd(Account actor, UUID providerId, String phone, String fullName) {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone);
        if (fullName != null) {
            body.put("fullName", fullName);
        }
        return send("POST", "/api/v1/providers/" + providerId + "/customers", actor.accessToken(), body);
    }

    /**
     * F-03: enrolment is an invitation. This does the whole dance the way real users would: the customer has (or gets) their
     * own profile name, the provider invites the number, the customer accepts. Returns the provider's view of the member.
     */
    private CustomerView add(Account actor, UUID providerId, String phone, String customerProfileName) {
        Account customer = accounts.get(phone);
        assertThat(customer).as("login(" + phone + ") first").isNotNull();
        if (customerProfileName != null && send("GET", "/api/v1/me/person", customer.accessToken(), null)
                .expectBody(String.class).returnResult().getStatus().value() == 404) {
            savePerson(customer, customerProfileName);
        }
        InvitationView invitation = invite(actor, providerId, phone);
        MyMembershipView membership = accept(customer, invitation.invitationId());
        return send("GET", "/api/v1/providers/" + providerId + "/customers/" + membership.membershipId(),
                actor.accessToken(), null).expectStatus().isOk().expectBody(CustomerView.class).returnResult()
                .getResponseBody();
    }

    private InvitationView invite(Account actor, UUID providerId, String phone) {
        return sendAdd(actor, providerId, phone, null).expectStatus().isAccepted()
                .expectBody(InvitationView.class).returnResult().getResponseBody();
    }

    private MyMembershipView accept(Account customer, UUID invitationId) {
        return send("POST", "/api/v1/me/invitations/" + invitationId + "/accept", customer.accessToken(), Map.of())
                .expectStatus().isOk().expectBody(MyMembershipView.class).returnResult().getResponseBody();
    }

    private List<MyInvitationView> myInvitations(Account customer) {
        MyInvitationView[] body = send("GET", "/api/v1/me/invitations", customer.accessToken(), null)
                .expectStatus().isOk().expectBody(MyInvitationView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<InvitationView> providerInvitations(Account actor, UUID providerId) {
        InvitationView[] body = send("GET", "/api/v1/providers/" + providerId + "/customers/invitations",
                actor.accessToken(), null).expectStatus().isOk().expectBody(InvitationView[].class).returnResult()
                .getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private String invitationStatusInDb(UUID invitationId) {
        return jdbc.queryForObject("select status from membership_invitation where id = ?", String.class, invitationId);
    }

    private List<CustomerView> listCustomers(Account actor, UUID providerId, boolean includeInactive) {
        CustomerView[] body = send("GET", "/api/v1/providers/" + providerId + "/customers?includeInactive="
                + includeInactive, actor.accessToken(), null)
                .expectStatus().isOk().expectBody(CustomerView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<MyMembershipView> myMemberships(Account account) {
        MyMembershipView[] body = send("GET", "/api/v1/me/memberships", account.accessToken(), null)
                .expectStatus().isOk().expectBody(MyMembershipView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private PersonView savePerson(Account account, String fullName) {
        return send("PUT", "/api/v1/me/person", account.accessToken(), Map.of("fullName", fullName))
                .expectStatus().isOk().expectBody(PersonView.class).returnResult().getResponseBody();
    }

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }

    private int statusOf(RestTestClient.ResponseSpec spec) {
        return spec.expectBody(String.class).returnResult().getStatus().value();
    }

    private int membershipRows(UUID providerId) {
        return jdbc.queryForObject("select count(*) from provider_membership where provider_id = ?", Integer.class,
                providerId);
    }

    private int personRows(UUID accountId) {
        return jdbc.queryForObject("select count(*) from person where user_account_id = ?", Integer.class,
                accountId);
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(java.util.concurrent.Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> results = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
