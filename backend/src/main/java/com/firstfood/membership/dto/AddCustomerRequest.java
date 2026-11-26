package com.firstfood.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code phone} is the number to invite (a lookup key only - never an authorization credential). {@code fullName} is
 * accepted for older clients and IGNORED (F-03): a provider never supplies or changes anyone's profile name; the customer
 * provides their own when they accept. There is deliberately no actor/provider/person id: the actor comes from the JWT and
 * the provider from the path.
 */
public record AddCustomerRequest(
        @NotBlank @Size(max = 32) String phone,
        @Size(max = 120) String fullName) {
}
