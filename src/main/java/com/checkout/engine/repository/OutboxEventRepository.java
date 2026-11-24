package com.checkout.engine.repository;

import com.checkout.engine.entity.OutboxEvent;
import com.checkout.engine.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingEventsWithSkipLocked(@Param("batchSize") int batchSize);

    List<OutboxEvent> findByStatus(OutboxStatus status);
}
