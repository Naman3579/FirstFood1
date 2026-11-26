package com.firstfood.discovery.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** {@code startDate} defaults to today. Price and terms are never client input: the server snapshots the plan. */
public record SubscribeRequest(@NotNull UUID planId, LocalDate startDate) {
}
