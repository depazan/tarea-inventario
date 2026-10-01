package com.store.inventory.repository;

import com.store.inventory.domain.ProductState;
import com.store.inventory.domain.ReserveResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * In-memory {@link InventoryRepository}, backed by {@link ConcurrentHashMap}s.
 * Data lives only in this JVM instance.
 */
public final class InMemoryInventoryRepository implements InventoryRepository {

    private final ConcurrentHashMap<String, ProductState> products = new ConcurrentHashMap<>();

    /** Bound to a SKU only once a reservation for that order id has succeeded. */
    private final ConcurrentHashMap<String, String> orderSkuIndex = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public boolean registerProduct(String sku, ProductState product) {
        return products.putIfAbsent(sku, product) == null;
    }

    /** {@inheritDoc} */
    @Override
    public ProductState findProduct(String sku) {
        return products.get(sku);
    }

    /** {@inheritDoc} */
    @Override
    public String findSkuByOrderId(String orderId) {
        return orderSkuIndex.get(orderId);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote {@code compute(...)} runs one order id at a time, so concurrent calls for
     *     the same order id never overlap. If {@code reservationAction} throws, the map
     *     stays unchanged and the order id is free to retry, even with a different SKU.
     */
    @Override
    public ReserveResult reserveForOrder(String orderId, String sku, Supplier<ReserveResult> reservationAction) {
        AtomicReference<ReserveResult> outcome = new AtomicReference<>();
        orderSkuIndex.compute(orderId, (id, boundSku) -> {
            if (boundSku != null && !boundSku.equals(sku)) {
                throw new IllegalArgumentException(
                        "Order " + orderId + " was already used to reserve product " + boundSku);
            }
            outcome.set(reservationAction.get());
            return sku;
        });
        return outcome.get();
    }
}
