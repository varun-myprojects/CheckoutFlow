package com.checkout.engine.service.lock;

import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;

import java.util.List;
import java.util.function.Supplier;

public interface InventoryLockStrategy {
    /**
     * Executes the given transactional action within an acquired distributed lock across the specified product IDs.
     * Default implementation simply executes the callback directly without distributed locking.
     *
     * @param productIds the product IDs to lock
     * @param transactionalAction the transactional callback to execute
     * @param <T> the result type
     * @return the result of the transactional action
     */
    default <T> T executeWithDistributedLock(List<Long> productIds, Supplier<T> transactionalAction) {
        return transactionalAction.get();
    }

    /**
     * Reserves inventory for the given cart items under the specified order.
     * Decrements available_qty and increments reserved_qty on each Inventory record,
     * and persists active InventoryReservation records with a 10-minute TTL.
     *
     * @param order the order for which inventory is reserved
     * @param cartItems the items to reserve
     * @return list of created InventoryReservation entities
     */
    List<InventoryReservation> reserveInventory(Order order, List<CartItem> cartItems);
}
