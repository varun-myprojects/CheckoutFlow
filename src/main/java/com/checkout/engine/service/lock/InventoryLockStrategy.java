package com.checkout.engine.service.lock;

import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;

import java.util.List;

public interface InventoryLockStrategy {
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
