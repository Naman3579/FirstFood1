package com.firstfood.closure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence only; scoped by provider so a closure id from another provider is never resolvable (no IDOR). */
public interface ProviderClosureRepository extends JpaRepository<ProviderClosure, UUID> {

    Optional<ProviderClosure> findByIdAndProviderId(UUID id, UUID providerId);

    List<ProviderClosure> findByProviderIdOrderByStartDateDescCreatedAtDesc(UUID providerId);

    Optional<ProviderClosure> findByProviderIdAndStartDateAndEndDateAndStatus(
            UUID providerId, LocalDate startDate, LocalDate endDate, ClosureStatus status);

    /** DECLARED closures of the provider that share a day with from..to (mirrors the closure_no_overlap constraint). */
    @Query("""
            select c from ProviderClosure c
            where c.providerId = :providerId
              and c.status = com.firstfood.closure.ClosureStatus.DECLARED
              and c.startDate <= :to and c.endDate >= :from
            """)
    List<ProviderClosure> findOverlapping(
            @Param("providerId") UUID providerId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
