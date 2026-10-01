package com.store.inventory.domain;

/**
 * Result of a stock change operation.
 */
public record StockChangeResult(boolean shouldAlert, int availableAfter) {
}
