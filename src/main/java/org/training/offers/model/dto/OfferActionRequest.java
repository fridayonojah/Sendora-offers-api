package org.training.offers.model.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record OfferActionRequest(@NotNull UUID userId) {
}
