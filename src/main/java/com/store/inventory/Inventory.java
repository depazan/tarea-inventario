package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.repository.InMemoryInventoryRepository;
import java.time.Clock;

import static java.util.Objects.requireNonNull;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        requireNonNull(clock, "clock");
        requireNonNull(alertListener, "alertListener");
        return new DefaultInventoryService(clock, alertListener, new InMemoryInventoryRepository());
    }
}
