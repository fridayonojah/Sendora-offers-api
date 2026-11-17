package org.training.offers.model.response;

import java.util.List;

public record PagedOfferResponse(
		List<OfferResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
