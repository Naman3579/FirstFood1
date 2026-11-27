package com.firstfood.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByPhone(String phone);

    boolean existsByPhone(String phone);

    /** Phone sign-in and every "who owns this number" question: only a VERIFIED number identifies an account (V29). */
    Optional<UserAccount> findByPhoneAndPhoneVerifiedTrue(String phone);

    boolean existsByPhoneAndPhoneVerifiedTrue(String phone);

    /**
     * Someone just PROVED this number, so anybody's earlier unproven claim on it is void. Those accounts always have a
     * verified email (an unverified number can only be added to an account that has no phone), so they stay valid.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update UserAccount a set a.phone = null where a.phone = :phone and a.phoneVerified = false and a.id <> :except")
    int releaseUnverifiedClaims(@org.springframework.data.repository.query.Param("phone") String phone,
            @org.springframework.data.repository.query.Param("except") java.util.UUID except);

    /** Only a VERIFIED email identifies an account (V28 unique index); an unproven address is never a login. */
    Optional<UserAccount> findByEmailAndEmailVerifiedTrue(String email);

    boolean existsByEmailAndEmailVerifiedTrue(String email);
}
