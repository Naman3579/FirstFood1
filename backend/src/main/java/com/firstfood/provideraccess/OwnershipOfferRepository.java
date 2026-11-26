package com.firstfood.provideraccess;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OwnershipOfferRepository extends JpaRepository<OwnershipOffer, UUID> {

    Optional<OwnershipOffer> findByProviderIdAndStatus(UUID providerId, OwnershipOfferStatus status);

    /** Existence checks on purpose: they must not load the entity ahead of {@link #findByIdForUpdate}. */
    boolean existsByIdAndProviderId(UUID id, UUID providerId);

    boolean existsByIdAndTargetPhone(UUID id, String targetPhone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnershipOffer o where o.id = :id")
    Optional<OwnershipOffer> findByIdForUpdate(@Param("id") UUID id);

    /** The provider an offer belongs to, without loading the offer. */
    @Query("select o.providerId from OwnershipOffer o where o.id = :id")
    Optional<UUID> findProviderIdById(@Param("id") UUID id);

    List<OwnershipOffer> findTop20ByProviderIdOrderByCreatedAtDesc(UUID providerId);

    List<OwnershipOffer> findByTargetPhoneAndStatusOrderByCreatedAtDesc(String targetPhone, OwnershipOfferStatus status);
}
