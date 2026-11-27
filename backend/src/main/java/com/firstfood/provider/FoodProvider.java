package com.firstfood.provider;

import com.firstfood.provider.error.InvalidProviderRequestException;
import com.firstfood.provider.error.InvalidProviderTransitionException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A food business (rules: never called "Restaurant"). Never physically
 * deleted after creation - closure is the terminal {@link ProviderStatus#CLOSED}
 * state with actor/time/reason retained (freeze #7).
 *
 * The status / accepting_new_customers / closure invariants are enforced here
 * (state-changing methods) AND by CHECK constraints in V3__provider_schema.sql;
 * the database is the last line of defence, this class gives the clean errors.
 */
@Entity
@Table(name = "food_provider")
public class FoodProvider {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    /** One or more kinds of business (e.g. MESS and TIFFIN); never empty. Stored in food_provider_type (V18). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "food_provider_type", joinColumns = @JoinColumn(name = "provider_id"))
    @Column(name = "provider_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Set<ProviderType> providerTypes = EnumSet.noneOf(ProviderType.class);

    @Column(length = 1000)
    private String description;

    @Column(name = "address_line", nullable = false, length = 255)
    private String addressLine;

    @Column(nullable = false, length = 120)
    private String locality;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 6)
    private String pincode;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    /** What the kitchen serves; the customer-side "food type" filter. */
    @Enumerated(EnumType.STRING)
    @Column(name = "food_type", nullable = false, length = 20)
    private FoodType foodType = FoodType.VEG_AND_NON_VEG;

    /** Decimal degrees (WGS84). Both set or both null (also enforced by a CHECK constraint, V16). */
    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderStatus status = ProviderStatus.ACTIVE;

    @Column(name = "accepting_new_customers", nullable = false)
    private boolean acceptingNewCustomers = true;

    @Column(name = "max_active_subscriptions")
    private Integer maxActiveSubscriptions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_account_id")
    private UUID closedByAccountId;

    @Column(name = "closure_reason", length = 500)
    private String closureReason;

    protected FoodProvider() {
        // JPA
    }

    public FoodProvider(
            String name, Set<ProviderType> providerTypes, String addressLine, String locality, String city,
            Integer maxActiveSubscriptions) {
        this.id = UUID.randomUUID();
        this.name = name;
        setProviderTypes(providerTypes);
        this.addressLine = addressLine;
        this.locality = locality;
        this.city = city;
        this.maxActiveSubscriptions = maxActiveSubscriptions;
    }

    /**
     * Moves to a different status. {@code acceptingRequested} is the
     * client's optional intake preference: only meaningful when the target is
     * ACTIVE (defaults to true there); every other status forces it false
     * and rejects an explicit true.
     */
    public void transitionTo(ProviderStatus target, Boolean acceptingRequested) {
        if (target == ProviderStatus.CLOSED) {
            throw new IllegalArgumentException("Use close(...) to close a provider.");
        }
        if (!status.canTransitionTo(target)) {
            throw new InvalidProviderTransitionException(status, target);
        }
        if (target == ProviderStatus.ACTIVE) {
            this.acceptingNewCustomers = acceptingRequested == null || acceptingRequested;
        } else {
            rejectAcceptingTrue(target, acceptingRequested);
            this.acceptingNewCustomers = false;
        }
        this.status = target;
    }

    /** Terminal closure: records who, when and (optionally) why. */
    public void close(UUID actorAccountId, String reason, Boolean acceptingRequested, Instant now) {
        if (!status.canTransitionTo(ProviderStatus.CLOSED)) {
            throw new InvalidProviderTransitionException(status, ProviderStatus.CLOSED);
        }
        rejectAcceptingTrue(ProviderStatus.CLOSED, acceptingRequested);
        this.status = ProviderStatus.CLOSED;
        this.acceptingNewCustomers = false;
        this.closedAt = now;
        this.closedByAccountId = actorAccountId;
        this.closureReason = reason;
    }

    /** Toggle customer intake without changing status (only legal while ACTIVE). */
    public void setIntake(boolean accepting) {
        if (accepting && status != ProviderStatus.ACTIVE) {
            throw new InvalidProviderRequestException(
                    "A provider that is " + status + " cannot accept new customers.");
        }
        this.acceptingNewCustomers = accepting;
    }

    private void rejectAcceptingTrue(ProviderStatus target, Boolean acceptingRequested) {
        if (Boolean.TRUE.equals(acceptingRequested)) {
            throw new InvalidProviderRequestException(
                    "A provider that is " + target + " cannot accept new customers.");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** A copy, ordered by the enum's declaration order, so responses are stable. */
    public Set<ProviderType> getProviderTypes() {
        return providerTypes.isEmpty() ? EnumSet.noneOf(ProviderType.class) : EnumSet.copyOf(providerTypes);
    }

    public void setProviderTypes(Set<ProviderType> types) {
        if (types == null || types.isEmpty()) {
            throw new InvalidProviderRequestException("Choose at least one provider type.");
        }
        this.providerTypes.clear();
        this.providerTypes.addAll(types);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public FoodType getFoodType() {
        return foodType;
    }

    public void setFoodType(FoodType foodType) {
        this.foodType = foodType;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    /** Sets or clears (both null) where the provider is. */
    public void setLocation(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidProviderRequestException("Send both latitude and longitude, or neither.");
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public boolean isAcceptingNewCustomers() {
        return acceptingNewCustomers;
    }

    public Integer getMaxActiveSubscriptions() {
        return maxActiveSubscriptions;
    }

    public void setMaxActiveSubscriptions(Integer maxActiveSubscriptions) {
        this.maxActiveSubscriptions = maxActiveSubscriptions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedByAccountId() {
        return closedByAccountId;
    }

    public String getClosureReason() {
        return closureReason;
    }
}
