package com.firstfood.membership.dto;

import com.firstfood.membership.AddressInput;
import jakarta.validation.constraints.Size;

/** Send all fields to save an address; send them all empty/null to remove it. The service checks completeness. */
public record SavePersonAddressRequest(
        @Size(max = 255) String addressLine,
        @Size(max = 120) String locality,
        @Size(max = 100) String city,
        @Size(max = 6) String pincode,
        @Size(max = 150) String landmark) {

    /** Null when everything is blank (meaning "remove my address"). */
    public AddressInput toInput() {
        boolean empty = blank(addressLine) && blank(locality) && blank(city) && blank(pincode) && blank(landmark);
        return empty ? null : new AddressInput(addressLine, locality, city, pincode, landmark);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
