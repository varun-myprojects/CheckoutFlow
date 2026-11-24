package com.checkout.engine.repository;

import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {
    List<InventoryReservation> findByOrderId(Long orderId);
    List<InventoryReservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);
    List<InventoryReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant now);
    List<InventoryReservation> findByProductIdAndStatus(Long productId, ReservationStatus status);

    @Query(value = "SELECT * FROM inventory_reservations WHERE status = 'ACTIVE' AND expires_at < NOW() FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<InventoryReservation> findExpiredActiveReservationsWithSkipLocked();

    @Query(value = "SELECT * FROM inventory_reservations WHERE status = 'ACTIVE' AND expires_at < :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<InventoryReservation> findExpiredActiveReservationsWithSkipLocked(@Param("now") Instant now);
}
