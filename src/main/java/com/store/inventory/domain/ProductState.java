package com.store.inventory.domain;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.policy.CategoryPolicy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds stock and reservation state for one SKU.
 *
 * <p>The lock protects updates for this SKU only, so other SKUs are never blocked.
 *
 * <p>Expired reservations are released lazily, using the given clock.
 *
 * <p>Reserving again with the same order id does not reserve twice.
 */
public final class ProductState {

    private static final int LOW_STOCK_THRESHOLD = 5;

    private final String sku;
    private final ProductCategory category;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ReservationState> reservationsByOrderId = new HashMap<>();
    private int stock;
    private int reservedUnits;
    private boolean lowStockAlertActive;

    public ProductState(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    public ProductCategory category() {
        return category;
    }

    /**
     * Adds units to stock and re-checks the low-stock alert.
     * Only this method can turn the alert back off.
     *
     * @param quantity units to add
     * @param now current time, used to release expired reservations first
     * @return whether to notify the low-stock listener, and the available units after adding stock
     */
    public StockChangeResult addStock(int quantity, Instant now) {
        lock.lock();
        try {
            purgeExpiredLocked(now);
            stock += quantity;
            int availableAfter = availableLocked();
            boolean shouldAlert = evaluateLowStock(availableAfter, true);
            return new StockChangeResult(shouldAlert, availableAfter);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Units still available to reserve: stock minus active reservations.
     * Expired reservations are released first, as a side effect of this call.
     *
     * @param now current time, used to find expired reservations
     * @return available units after releasing any expired reservation
     */
    public int available(Instant now) {
        lock.lock();
        try {
            purgeExpiredLocked(now);
            return availableLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves {@code quantity} units for {@code orderId} under the given {@code policy}.
     * Same order id and quantity returns the existing reservation instead of reserving again.
     *
     * <p>{@code expiresAt} is {@code now} plus the policy's TTL.
     *
     * <p>This call can only turn the low-stock alert on, never off: units freed by an
     * expired reservation don't count as restocking.
     *
     * @param orderId caller-supplied identifier used for idempotent replay
     * @param quantity units to reserve
     * @param policy reservation TTL and per-order limit for this category
     * @param now current time, used to release expired reservations and compute {@code expiresAt}
     * @return the reservation plus whether to notify the low-stock listener
     * @throws OrderLimitExceededException if quantity exceeds the category's per-order limit
     * @throws InsufficientStockException if there aren't enough available units
     * @throws IllegalArgumentException if this order id was reserved before with a different quantity
     * @throws IllegalStateException if this order id was already confirmed or has expired
     */
    public ReserveResult reserve(String orderId, int quantity, CategoryPolicy policy, Instant now) {
        lock.lock();
        try {
            purgeExpiredLocked(now);

            ReservationState existing = reservationsByOrderId.get(orderId);
            if (existing != null) {
                return replay(existing, quantity);
            }

            if (quantity > policy.maxUnitsPerOrder()) {
                throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
            }
            int availableBefore = availableLocked();
            if (quantity > availableBefore) {
                throw new InsufficientStockException(sku, quantity, availableBefore);
            }

            Instant expiresAt = now.plus(policy.reservationTtl());
            ReservationState reservation = new ReservationState(orderId, sku, quantity, expiresAt);
            reservationsByOrderId.put(orderId, reservation);
            reservedUnits += quantity;

            int availableAfter = availableLocked();
            boolean shouldAlert = evaluateLowStock(availableAfter, false);
            return new ReserveResult(reservation.toReservation(), shouldAlert, availableAfter);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Confirms an active, non-expired reservation. Confirmed units leave stock
     * for good and never return to the available pool.
     *
     * @param orderId identifier of a previously created reservation
     * @param now current time, used to check if the reservation has expired
     * @throws IllegalStateException if the order id has no active reservation
     */
    public void confirm(String orderId, Instant now) {
        lock.lock();
        try {
            purgeExpiredLocked(now);
            ReservationState reservation = reservationsByOrderId.get(orderId);
            if (reservation == null || reservation.status() != ReservationState.Status.ACTIVE) {
                throw new IllegalStateException("Order " + orderId + " has no active reservation");
            }
            reservation.markConfirmed();
            reservedUnits -= reservation.quantity();
            stock -= reservation.quantity();
        } finally {
            lock.unlock();
        }
    }

    private ReserveResult replay(ReservationState existing, int quantity) {
        if (existing.quantity() != quantity) {
            throw new IllegalArgumentException(
                    "Order " + existing.orderId() + " was already reserved with a different quantity");
        }
        if (existing.status() != ReservationState.Status.ACTIVE) {
            throw new IllegalStateException(
                    "Order " + existing.orderId() + " cannot be reserved again after being " + existing.status());
        }
        return new ReserveResult(existing.toReservation(), false, availableLocked());
    }

    /** Releases expired reservations. Must be called while holding the lock. */
    private void purgeExpiredLocked(Instant now) {
        for (ReservationState reservation : reservationsByOrderId.values()) {
            if (reservation.status() == ReservationState.Status.ACTIVE && !now.isBefore(reservation.expiresAt())) {
                reservation.markExpired();
                reservedUnits -= reservation.quantity();
            }
        }
    }

    private int availableLocked() {
        return stock - reservedUnits;
    }

    /**
     * Turns the low-stock alert on or off after a stock change.
     *
     * @param availableAfter units available after the change
     * @param allowRearm true only from {@link #addStock}, the only method that can turn an active alert back off
     * @return true if the caller should notify the low-stock listener
     */
    private boolean evaluateLowStock(int availableAfter, boolean allowRearm) {
        if (!lowStockAlertActive && availableAfter <= LOW_STOCK_THRESHOLD) {
            lowStockAlertActive = true;
            return true;
        }
        if (allowRearm && lowStockAlertActive && availableAfter > LOW_STOCK_THRESHOLD) {
            lowStockAlertActive = false;
        }
        return false;
    }
}
