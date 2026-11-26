package com.firstfood.subscription.dto;

import java.time.LocalDate;

/** The first day the customer is away. Omitted = today. There is deliberately no return date. */
public record StartVacationRequest(LocalDate startDate) {
}
