package com.firstfood.membership;

/** A customer's address. Always whole: line, locality and city are present; pincode and landmark are optional. */
public record AddressView(String addressLine, String locality, String city, String pincode, String landmark) {

    /** Null when the person has not saved an address. */
    static AddressView of(Person p) {
        return p.getAddressLine() == null ? null
                : new AddressView(p.getAddressLine(), p.getLocality(), p.getCity(), p.getPincode(), p.getLandmark());
    }
}
