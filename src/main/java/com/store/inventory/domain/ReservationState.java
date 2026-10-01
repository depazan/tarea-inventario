package com.store.inventory.domain;

import com.store.inventory.api.Reservation;
import java.time.Instant;

/**
 * Mutable state of a reservation: starts ACTIVE, then becomes CONFIRMED or EXPIRED.
 * Changes only happen while ProductState holds its lock.
 */
final class ReservationState {

    enum Status {
        ACTIVE, CONFIRMED, EXPIRED
    }

    private final String orderId;
    private final String sku;
    private final int quantity;
    private final Instant expiresAt;
    private Status status;

    ReservationState(String orderId, String sku, int quantity, Instant expiresAt) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.status = Status.ACTIVE;
    }

    String orderId() {
        return orderId;
    }

    int quantity() {
        return quantity;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Status status() {
        return status;
    }

    /**
     * Marks this reservation as confirmed.
     *
     * @throws IllegalStateException if the reservation is not active
     */
    void markConfirmed() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException(
                    "Only active reservations can be confirmed");
        }
        status = Status.CONFIRMED;
    }

    /**
     * Marks this reservation as expired.
     *
     * @throws IllegalStateException if the reservation is not active
     */
    void markExpired() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException(
                    "Only active reservations can expire");
        }
        status = Status.EXPIRED;
    }

    Reservation toReservation() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }
}
