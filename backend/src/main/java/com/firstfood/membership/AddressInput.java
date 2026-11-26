package com.firstfood.membership;

/** What a customer typed for their address; validated and trimmed by the service. */
public record AddressInput(String addressLine, String locality, String city, String pincode, String landmark) {
}
