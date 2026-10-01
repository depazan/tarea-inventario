package com.store.inventory.application;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.ProductState;
import com.store.inventory.domain.ReserveResult;
import com.store.inventory.domain.StockChangeResult;
import com.store.inventory.domain.policy.CategoryPolicies;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.repository.InventoryRepository;
import java.time.Clock;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link InventoryService}.
 *
 * <p>Stock changes are isolated per SKU, so different products don't block each other.
 *
 * <p>Reservations are idempotent by order id.
 *
 * <p>Storage goes through {@link InventoryRepository}.
 */
public final class DefaultInventoryService implements InventoryService {

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final InventoryRepository repository;

    public DefaultInventoryService(Clock clock, StockAlertListener alertListener, InventoryRepository repository) {
        this.clock = requireNonNull(clock, "clock");
        this.alertListener = requireNonNull(alertListener, "alertListener");
        this.repository = requireNonNull(repository, "repository");
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Rejects duplicates so a product's category (and policy) can't change silently.
     */
    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireNonNull(sku, "sku");
        requireNonNull(category, "category");
        boolean registered = repository.registerProduct(sku, new ProductState(sku, category));
        if (!registered) {
            throw new IllegalStateException("Product " + sku + " is already registered");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Stock and the low-stock alert are updated under the SKU's lock; the
     *     listener runs after that lock is released.
     */
    @Override
    public void addStock(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        ProductState product = repository.findProduct(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product " + sku + " is not registered");
        }
        StockChangeResult result = product.addStock(quantity, clock.instant());
        if (result.shouldAlert()) {
            alertListener.onLowStock(sku, result.availableAfter());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Idempotent per order id: concurrent calls can't create duplicate
     *     reservations, and a failed attempt leaves the order id free to retry.
     */
    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireNonNull(orderId, "orderId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        ProductState product = getProductForReservation(sku, quantity);
        CategoryPolicy policy = CategoryPolicies.of(product.category());
        Instant now = clock.instant();

        ReserveResult result = repository.reserveForOrder(
                orderId, sku, () -> product.reserve(orderId, quantity, policy, now));
        notifyLowStockIfNeeded(sku, result);
        return result.reservation();
    }

    private ProductState getProductForReservation(String sku, int quantity) {
        ProductState product = repository.findProduct(sku);
        if (product == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }
        return product;
    }

    private void notifyLowStockIfNeeded(String sku, ReserveResult result) {
        if (result.shouldAlert()) {
            alertListener.onLowStock(sku, result.availableAfter());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Looks up the SKU by order id, since the contract confirms by order id only.
     */
    @Override
    public void confirm(String orderId) {
        requireNonNull(orderId, "orderId");
        String sku = repository.findSkuByOrderId(orderId);
        if (sku == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        repository.findProduct(sku).confirm(orderId, clock.instant());
    }

    /** {@inheritDoc} */
    @Override
    public int available(String sku) {
        ProductState product = repository.findProduct(sku);
        if (product == null) {
            return 0;
        }
        return product.available(clock.instant());
    }
}
