package org.training.offers.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.dto.OfferActionRequest;
import org.training.offers.model.dto.OfferFilterRequest;
import org.training.offers.model.response.OfferResponse;
import org.training.offers.model.response.PagedOfferResponse;
import org.training.offers.service.OfferService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/offers")
public class OfferController {

	private final OfferService offerService;

	public OfferController(OfferService offerService) {
		this.offerService = offerService;
	}

	@PostMapping
	public ResponseEntity<OfferResponse> createOffer(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CreateOfferRequest request) {
		OfferResponse created = offerService.createOffer(idempotencyKey, request);
		return ResponseEntity.created(URI.create("/api/v1/offers/" + created.id())).body(created);
	}

	@GetMapping
	public PagedOfferResponse listOffers(
			@RequestParam(required = false) CurrencyCode sourceCurrency,
			@RequestParam(required = false) CurrencyCode targetCurrency,
			@RequestParam(required = false) OfferStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(defaultValue = "createdAt,desc") String sort) {
		return offerService.listOffers(new OfferFilterRequest(sourceCurrency, targetCurrency, status, page, size, sort));
	}

	@GetMapping("/{id}")
	public OfferResponse getOffer(@PathVariable UUID id) {
		return offerService.getOffer(id);
	}

	@PostMapping("/{id}/accept")
	public OfferResponse acceptOffer(@PathVariable UUID id, @Valid @RequestBody OfferActionRequest request) {
		return offerService.acceptOffer(id, request);
	}

	@PostMapping("/{id}/cancel")
	public OfferResponse cancelOffer(@PathVariable UUID id, @Valid @RequestBody OfferActionRequest request) {
		return offerService.cancelOffer(id, request);
	}
}
