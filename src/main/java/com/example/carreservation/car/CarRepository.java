package com.example.carreservation.car;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CarRepository extends JpaRepository<Car, UUID> {

    Slice<Car> findAllBy(Pageable pageable);

    /**
     * Locks the car row until the end of the transaction. {@code FOR NO KEY UPDATE} is the weakest
     * lock that conflicts with itself; unlike {@code FOR UPDATE} (Hibernate 6.6's PESSIMISTIC_WRITE)
     * it does not block foreign-key checks of other transactions.
     */
    @Query(value = "SELECT id FROM car WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<UUID> lockById(UUID id);
}
