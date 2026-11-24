package com.checkout.engine.repository;

import com.checkout.engine.entity.SavedItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface SavedItemRepository extends JpaRepository<SavedItem, Long> {

    @Query("SELECT si FROM SavedItem si JOIN FETCH si.product WHERE si.user.id = :userId")
    List<SavedItem> findByUserId(@Param("userId") Long userId);

    @Query("SELECT si FROM SavedItem si JOIN FETCH si.product WHERE si.user.id = :userId AND si.product.id = :productId")
    Optional<SavedItem> findByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    @Query("SELECT si FROM SavedItem si JOIN FETCH si.user JOIN FETCH si.product WHERE si.product.id = :productId AND si.priceAtSave > :newPrice")
    List<SavedItem> findByProductIdAndPriceAtSaveGreaterThan(@Param("productId") Long productId, @Param("newPrice") BigDecimal newPrice);

    void deleteByUserIdAndProductId(Long userId, Long productId);
}
