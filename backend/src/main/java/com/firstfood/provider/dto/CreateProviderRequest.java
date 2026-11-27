package com.firstfood.provider.dto;

import com.firstfood.provider.FoodType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import com.firstfood.identity.dto.PhonePattern;
import com.firstfood.provider.ProviderType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Deliberately has no owner/role/status fields: the creator always becomes
 * OWNER (chosen by the backend from the authenticated principal) and a new
 * provider always starts ACTIVE + accepting. Unknown JSON properties sent by
 * a client are ignored, never trusted.
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 120) String name,
        @NotEmpty Set<@NotNull ProviderType> providerTypes,
        FoodType foodType,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 255) String addressLine,
        @NotBlank @Size(max = 120) String locality,
        @NotBlank @Size(max = 100) String city,
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Enter a valid 6-digit pincode") String pincode,
        @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String contactPhone,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @Min(1) Integer maxActiveSubscriptions) {
}
