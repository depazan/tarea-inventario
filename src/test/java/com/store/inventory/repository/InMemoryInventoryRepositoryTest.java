package com.store.inventory.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.domain.ProductState;
import com.store.inventory.domain.ReserveResult;
import com.store.inventory.domain.policy.CategoryPolicies;
import com.store.inventory.domain.policy.CategoryPolicy;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryInventoryRepositoryTest {

    private final InMemoryInventoryRepository repository = new InMemoryInventoryRepository();

    private static ReserveResult reserveResult(String orderId, String sku, int quantity) {
        return new ReserveResult(new Reservation(orderId, sku, quantity, Instant.EPOCH), false, 0);
    }

    @Test
    void firstRegistrationReturnsTrue() {
        boolean registered = repository.registerProduct("SKU-1", new ProductState("SKU-1", ProductCategory.STANDARD));

        assertTrue(registered);
    }

    @Test
    void duplicateRegistrationReturnsFalse() {
        repository.registerProduct("SKU-1", new ProductState("SKU-1", ProductCategory.STANDARD));

        boolean registeredAgain =
                repository.registerProduct("SKU-1", new ProductState("SKU-1", ProductCategory.STANDARD));

        assertFalse(registeredAgain);
    }

    @Test
    void findProductReturnsRegisteredProduct() {
        ProductState product = new ProductState("SKU-1", ProductCategory.STANDARD);
        repository.registerProduct("SKU-1", product);

        assertEquals(product, repository.findProduct("SKU-1"));
    }

    @Test
    void findUnknownProductReturnsNull() {
        assertNull(repository.findProduct("UNKNOWN"));
    }

    @Test
    void skuForUnknownOrderIsNull() {
        assertNull(repository.findSkuByOrderId("ORDER-1"));
    }

    @Test
    void reserveBindsOrderToSku() {
        repository.reserveForOrder("ORDER-1", "SKU-1", () -> reserveResult("ORDER-1", "SKU-1", 1));

        assertEquals("SKU-1", repository.findSkuByOrderId("ORDER-1"));
    }

    @Test
    void failedReservationLeavesOrderUnbound() {
        assertThrows(IllegalStateException.class, () -> repository.reserveForOrder(
                "ORDER-1", "SKU-1", () -> {
                    throw new IllegalStateException("boom");
                }));

        assertNull(repository.findSkuByOrderId("ORDER-1"));
    }

    @Test
    void failedReservationDoesNotConsumeOrderId() {
        assertThrows(IllegalStateException.class, () -> repository.reserveForOrder(
                "ORDER-1", "SKU-1", () -> {
                    throw new IllegalStateException("boom");
                }));

        ReserveResult result =
                repository.reserveForOrder("ORDER-1", "SKU-1", () -> reserveResult("ORDER-1", "SKU-1", 1));

        assertEquals("SKU-1", repository.findSkuByOrderId("ORDER-1"));
        assertEquals("ORDER-1", result.reservation().orderId());
    }

    @Test
    void failedReservationAllowsAnotherSku() {
        assertThrows(IllegalStateException.class, () -> repository.reserveForOrder(
                "ORDER-1", "SKU-1", () -> {
                    throw new IllegalStateException("boom");
                }));

        repository.reserveForOrder("ORDER-1", "SKU-2", () -> reserveResult("ORDER-1", "SKU-2", 1));

        assertEquals("SKU-2", repository.findSkuByOrderId("ORDER-1"));
    }

    @Test
    void boundOrderRejectsDifferentSku() {
        repository.reserveForOrder("ORDER-1", "SKU-1", () -> reserveResult("ORDER-1", "SKU-1", 1));

        assertThrows(IllegalArgumentException.class, () -> repository.reserveForOrder(
                "ORDER-1", "SKU-2", () -> reserveResult("ORDER-1", "SKU-2", 1)));
    }

    @Test
    void sameOrderConcurrentRetryReturnsSameReservation() throws Exception {
        ProductState product = new ProductState("SKU-1", ProductCategory.STANDARD);
        product.addStock(10, Instant.EPOCH);
        CategoryPolicy policy = CategoryPolicies.of(ProductCategory.STANDARD);

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ReserveResult> task = () -> {
                startLatch.await();
                return repository.reserveForOrder(
                        "ORDER-1", "SKU-1", () -> product.reserve("ORDER-1", 2, policy, Instant.EPOCH));
            };

            Future<ReserveResult> first = executor.submit(task);
            Future<ReserveResult> second = executor.submit(task);
            startLatch.countDown();

            ReserveResult firstResult = first.get(5, TimeUnit.SECONDS);
            ReserveResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertEquals(firstResult.reservation(), secondResult.reservation());
            assertEquals(8, product.available(Instant.EPOCH));
            assertEquals("SKU-1", repository.findSkuByOrderId("ORDER-1"));
        } finally {
            executor.shutdownNow();
        }
    }
}
