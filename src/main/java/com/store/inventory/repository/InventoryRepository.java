package com.store.inventory.repository;

import com.store.inventory.domain.ProductState;
import com.store.inventory.domain.ReserveResult;
import java.util.function.Supplier;

/**
 * Storage abstraction for products and reservations, so the service doesn't
 * depend on how the data is kept (in memory, a database, etc.).
 */
public interface InventoryRepository {

    /**
     * Registers a product if its SKU is not already taken.
     *
     * @param sku product identifier
     * @param product state to store for this SKU
     * @return true if the product was registered, false if the SKU was already in use
     */
    boolean registerProduct(String sku, ProductState product);

    /**
     * Finds a registered product by SKU.
     *
     * @param sku product identifier
     * @return the stored product state, or null if the SKU is not registered
     */
    ProductState findProduct(String sku);

    /**
     * Finds which SKU an order id is bound to.
     *
     * @param orderId order identifier
     * @return the SKU this order id is bound to, or null if it was never reserved
     */
    String findSkuByOrderId(String orderId);

    /**
     * Runs {@code reservationAction} and binds {@code orderId} to {@code sku} only on
     * success. Safe for concurrent calls with the same order id.
     *
     * @param orderId order identifier
     * @param sku product this order id is being reserved against
     * @param reservationAction performs the actual reservation and returns its result
     * @return the result returned by {@code reservationAction}
     * @throws IllegalArgumentException if this order id is already bound to a different SKU
     */
    ReserveResult reserveForOrder(String orderId, String sku, Supplier<ReserveResult> reservationAction);
}
