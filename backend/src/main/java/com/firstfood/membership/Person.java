package com.firstfood.membership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The individual who receives food (rules.md Rule 3.1: UserAccount != Person).
 * Holds the owning account as a plain id so membership does not depend on the
 * identity module's entities. There is intentionally no uniqueness on the
 * account id: one account may own many persons (Rule 3.2); only the single
 * "primary" (self) person is unique per account.
 */
@Entity
@Table(name = "person")
public class Person {

    @Id
    private UUID id;

    @Column(name = "user_account_id", nullable = false, updatable = false)
    private UUID userAccountId;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "is_primary", nullable = false, updatable = false)
    private boolean primaryPerson;

    @Column(name = "address_line", length = 255)
    private String addressLine;

    @Column(length = 120)
    private String locality;

    @Column(length = 100)
    private String city;

    @Column(length = 6)
    private String pincode;

    @Column(length = 150)
    private String landmark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Person() {
        // JPA
    }

    public Person(UUID userAccountId, String fullName, boolean primaryPerson) {
        this.id = UUID.randomUUID();
        this.userAccountId = Objects.requireNonNull(userAccountId, "userAccountId");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.primaryPerson = primaryPerson;
    }

    public void rename(String newFullName) {
        this.fullName = Objects.requireNonNull(newFullName, "fullName");
    }

    /** Sets the address, or clears it when {@code addressLine} is null. The caller has already validated the values. */
    public void changeAddress(String addressLine, String locality, String city, String pincode, String landmark) {
        this.addressLine = addressLine;
        this.locality = addressLine == null ? null : locality;
        this.city = addressLine == null ? null : city;
        this.pincode = addressLine == null ? null : pincode;
        this.landmark = addressLine == null ? null : landmark;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getLocality() {
        return locality;
    }

    public String getCity() {
        return city;
    }

    public String getPincode() {
        return pincode;
    }

    public String getLandmark() {
        return landmark;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserAccountId() {
        return userAccountId;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isPrimaryPerson() {
        return primaryPerson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
