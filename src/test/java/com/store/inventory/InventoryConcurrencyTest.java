package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InventoryConcurrencyTest {

    @Test
    void onlyOneReservationGetsLastUnit() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 1);

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Reservation> reserveAsOrder1 = () -> {
                startLatch.await();
                return service.reserve("ORDER-1", "SKU-1", 1);
            };
            Callable<Reservation> reserveAsOrder2 = () -> {
                startLatch.await();
                return service.reserve("ORDER-2", "SKU-1", 1);
            };

            Future<Reservation> first = executor.submit(reserveAsOrder1);
            Future<Reservation> second = executor.submit(reserveAsOrder2);
            startLatch.countDown();

            int succeeded = 0;
            int rejected = 0;
            for (Future<Reservation> future : List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof InsufficientStockException);
                    rejected++;
                }
            }

            assertEquals(1, succeeded);
            assertEquals(1, rejected);
            assertEquals(0, service.available("SKU-1"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameOrderConcurrentRetryReturnsSameReservation() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Reservation> reserveTask = () -> {
                startLatch.await();
                return service.reserve("ORDER-1", "SKU-1", 2);
            };

            Future<Reservation> first = executor.submit(reserveTask);
            Future<Reservation> second = executor.submit(reserveTask);
            startLatch.countDown();

            Reservation firstReservation = first.get(5, TimeUnit.SECONDS);
            Reservation secondReservation = second.get(5, TimeUnit.SECONDS);

            assertEquals(firstReservation, secondReservation);
            assertEquals(8, service.available("SKU-1"));
        } finally {
            executor.shutdownNow();
        }
    }
}
