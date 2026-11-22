package com.firstfood.identity;

import com.firstfood.identity.dto.PhonePattern;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The FirstFood login/authentication identity (rules.md Rule 3.1).
 *
 * Deliberately NOT the person who eats (rules.md Rule 3.2) - Person is
 * introduced in Phase 5. Do not add a unique constraint or 1:1 assumption
 * anywhere that would prevent one UserAccount from later owning multiple
 * Persons.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    /** Null for an account that signed up with a verified email only (V28). */
    @Column(unique = true, length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    /** True once the number was proven with an SMS code (V29). An unverified number is only a contact/matching hint. */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** The name collected on the sign-up form; null for accounts that predate it. */
    @Column(name = "full_name", length = 120)
    private String fullName;

    /** When the account registered on the customer portal; null = not registered there. */
    @Column(name = "customer_access_at")
    private Instant customerAccessAt;

    /** When the account registered on the staff portal; null = not registered there. */
    @Column(name = "staff_access_at")
    private Instant staffAccessAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String phone) {
        this.id = UUID.randomUUID();
        this.phone = PhonePattern.addPlus(phone);
        this.phoneVerified = this.phone != null; // an account created from a number was created by proving it
    }

    /** An account created by proving an email address (no phone yet). {@code email} must already be lower-cased. */
    public static UserAccount withVerifiedEmail(String email) {
        UserAccount account = new UserAccount((String) null);
        account.setVerifiedEmail(email);
        return account;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    /** Stores a number whose ownership was just proven with a code. */
    public void setPhone(String phone) {
        this.phone = PhonePattern.addPlus(phone);
        this.phoneVerified = this.phone != null;
    }

    /** Stores a number nobody has proven (see {@link PhoneProperties}). */
    public void setUnverifiedPhone(String phone) {
        this.phone = PhonePattern.addPlus(phone);
        this.phoneVerified = false;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public String getEmail() {
        return email;
    }

    /** Stores an email that has NOT been proven with a code (the original PATCH /me/email). */
    public void setEmail(String email) {
        this.email = email;
        this.emailVerified = false;
    }

    /** Stores an email whose ownership was proven with an emailed code. */
    public void setVerifiedEmail(String email) {
        this.email = email;
        this.emailVerified = true;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean hasPortal(Portal portal) {
        return (portal == Portal.CUSTOMER ? customerAccessAt : staffAccessAt) != null;
    }

    /** Registers the account on the portal (idempotent: an existing registration keeps its original timestamp). */
    public void registerOn(Portal portal, Instant at) {
        if (portal == Portal.CUSTOMER) {
            if (customerAccessAt == null) {
                customerAccessAt = at;
            }
        } else if (staffAccessAt == null) {
            staffAccessAt = at;
        }
    }

    /** Registered portals, customer first. */
    public java.util.List<Portal> portals() {
        java.util.List<Portal> result = new java.util.ArrayList<>(2);
        if (customerAccessAt != null) {
            result.add(Portal.CUSTOMER);
        }
        if (staffAccessAt != null) {
            result.add(Portal.STAFF);
        }
        return java.util.List.copyOf(result);
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
