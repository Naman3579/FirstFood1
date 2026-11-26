package com.firstfood.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface MembershipInvitationRepository extends JpaRepository<MembershipInvitation, UUID> {

    Optional<MembershipInvitation> findByProviderIdAndInvitedPhoneAndStatus(
            UUID providerId, String invitedPhone, InvitationStatus status);

    /**
     * Ownership checks are existence queries on purpose: they must not load the entity, or a following
     * {@link #findByIdForUpdate} would hand back that stale managed copy instead of the freshly locked row.
     */
    boolean existsByIdAndProviderId(UUID id, UUID providerId);

    boolean existsByIdAndInvitedPhone(UUID id, String invitedPhone);

    /** Row lock: two parallel accept/decline/withdraw calls on one invitation must not both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MembershipInvitation i where i.id = :id")
    Optional<MembershipInvitation> findByIdForUpdate(@Param("id") UUID id);

    List<MembershipInvitation> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    List<MembershipInvitation> findByInvitedPhoneAndStatusOrderByCreatedAtDesc(String invitedPhone, InvitationStatus status);
}
