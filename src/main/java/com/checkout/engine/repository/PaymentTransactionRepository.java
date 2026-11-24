package com.checkout.engine.repository;

import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);
    List<PaymentTransaction> findByOrderId(Long orderId);
    List<PaymentTransaction> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
