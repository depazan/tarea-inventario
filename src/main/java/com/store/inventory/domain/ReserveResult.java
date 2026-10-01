package com.store.inventory.domain;

import com.store.inventory.api.Reservation;

/**
 * Result of a reservation operation.
 */
public record ReserveResult(Reservation reservation, boolean shouldAlert, int availableAfter) {
}
