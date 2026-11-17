package org.training.offers.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.offers.model.entity.Offer;

import jakarta.persistence.LockModeType;

public interface OfferRepository extends JpaRepository<Offer, UUID>, JpaSpecificationExecutor<Offer> {

	// SELECT ... FOR UPDATE serialises concurrent accept/cancel on the same row.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Offer o where o.id = :id")
	Optional<Offer> findByIdForUpdate(@Param("id") UUID id);
}
