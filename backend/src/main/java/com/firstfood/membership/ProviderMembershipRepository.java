package com.firstfood.membership;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderMembershipRepository extends JpaRepository<ProviderMembership, UUID> {

    /** At most one ACTIVE row per (provider, person) - enforced by a partial unique index. */
    Optional<ProviderMembership> findByProviderIdAndPersonIdAndStatus(
            UUID providerId, UUID personId, MembershipStatus status);

    /** Scoped by provider so a membership id from another provider is never resolvable. */
    Optional<ProviderMembership> findByIdAndProviderId(UUID id, UUID providerId);

    List<ProviderMembership> findByProviderIdAndStatusOrderByJoinedAtDesc(UUID providerId, MembershipStatus status);

    List<ProviderMembership> findByProviderIdOrderByJoinedAtDesc(UUID providerId);

    List<ProviderMembership> findByPersonIdInOrderByJoinedAtDesc(Collection<UUID> personIds);

    /** Scalar read (does not load the entity), used to find the provider to lock before re-reading the row fresh. */
    @Query("select m.providerId from ProviderMembership m where m.id = :id and m.personId in :personIds")
    Optional<UUID> findProviderIdByIdAndPersonIdIn(@Param("id") UUID id, @Param("personIds") Collection<UUID> personIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ProviderMembership m where m.id = :id")
    Optional<ProviderMembership> findByIdForUpdate(@Param("id") UUID id);
}
