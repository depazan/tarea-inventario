package com.store.inventory.domain.policy;

import java.time.Duration;

/**
 * Defines the reservation rules for a product category.
 *
 * @param reservationTtl how long the reservation stays active
 * @param maxUnitsPerOrder maximum units allowed per order
 */
public record CategoryPolicy(Duration reservationTtl, int maxUnitsPerOrder) {

    /** Sentinel meaning "no per-order limit", avoiding an {@code Optional<Integer>} field. */
    public static final int UNLIMITED = Integer.MAX_VALUE;
}
