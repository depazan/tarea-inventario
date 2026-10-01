package com.store.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.repository.InMemoryInventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultInventoryServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2024-01-01T00:00:00Z");

    private final AtomicInteger alertCount = new AtomicInteger();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        StockAlertListener listener = (sku, available) -> alertCount.incrementAndGet();
        service = new DefaultInventoryService(clock, listener, new InMemoryInventoryRepository());
    }

    @Test
    void registerProductWorks() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void duplicateRegistrationThrows() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalStateException.class, () -> service.registerProduct("SKU-1", ProductCategory.STANDARD));
    }

    @Test
    void addStockNonPositiveThrows() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
    }

    @Test
    void addStockUnknownSkuThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 1));
    }

    @Test
    void reserveNonPositiveThrows() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0));
    }

    @Test
    void reserveUnknownSkuThrowsInsufficientStock() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "UNKNOWN", 1));
    }

    @Test
    void unknownSkuAvailableIsZero() {
        assertEquals(0, service.available("UNKNOWN"));
    }

    @Test
    void confirmUnknownOrderThrows() {
        assertThrows(IllegalStateException.class, () -> service.confirm("UNKNOWN-ORDER"));
    }

    @Test
    void reserveThenConfirmWorks() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        Reservation reservation = service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(6, service.available("SKU-1"));

        service.confirm("ORDER-1");

        assertEquals(6, service.available("SKU-1"));
        assertEquals("SKU-1", reservation.sku());
        assertEquals(4, reservation.quantity());
    }

    @Test
    void raisesLowStockAlert() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 6);

        assertEquals(1, alertCount.get());
    }

    @Test
    void doesNotRepeatLowStockAlert() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);

        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(1, alertCount.get());
    }

    @Test
    void addStockRearmsLowStockAlert() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);

        service.addStock("SKU-1", 20);

        assertEquals(1, alertCount.get());
    }

    @Test
    void rearmAllowsLowStockAgain() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);
        service.addStock("SKU-1", 20);

        service.reserve("ORDER-2", "SKU-1", 20);

        assertEquals(2, alertCount.get());
    }
}
