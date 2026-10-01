package com.store.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.domain.policy.CategoryPolicies;
import com.store.inventory.domain.policy.CategoryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProductStateTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = CategoryPolicies.of(ProductCategory.STANDARD);
    private static final CategoryPolicy PRE_ORDER = CategoryPolicies.of(ProductCategory.PRE_ORDER);
    private static final CategoryPolicy FLASH_SALE = CategoryPolicies.of(ProductCategory.FLASH_SALE);

    @Nested
    class StockAndReservations {

        private final ProductState state = new ProductState("SKU-1", ProductCategory.STANDARD);

        @Test
        void addStockIncreasesAvailable() {
            state.addStock(10, T0);

            assertEquals(10, state.available(T0));
        }

        @Test
        void reserveReducesAvailable() {
            state.addStock(10, T0);

            state.reserve("ORDER-1", 3, STANDARD, T0);

            assertEquals(7, state.available(T0));
        }

        @Test
        void insufficientStockThrows() {
            state.addStock(2, T0);

            assertThrows(InsufficientStockException.class, () -> state.reserve("ORDER-1", 3, STANDARD, T0));
        }

        @Test
        void confirmRemovesUnitsForGood() {
            state.addStock(5, T0);
            state.reserve("ORDER-1", 2, STANDARD, T0);

            state.confirm("ORDER-1", T0);

            assertEquals(3, state.available(T0));
        }

        @Test
        void confirmTwiceThrows() {
            state.addStock(5, T0);
            state.reserve("ORDER-1", 2, STANDARD, T0);
            state.confirm("ORDER-1", T0);

            assertThrows(IllegalStateException.class, () -> state.confirm("ORDER-1", T0));
        }
    }

    @Nested
    class Idempotency {

        private final ProductState state = new ProductState("SKU-1", ProductCategory.STANDARD);

        @Test
        void sameOrderReturnsSameReservation() {
            state.addStock(10, T0);

            Reservation first = state.reserve("ORDER-1", 3, STANDARD, T0).reservation();
            Reservation second = state.reserve("ORDER-1", 3, STANDARD, T0).reservation();

            assertEquals(first, second);
        }

        @Test
        void sameOrderDoesNotReserveTwice() {
            state.addStock(10, T0);

            state.reserve("ORDER-1", 3, STANDARD, T0);
            state.reserve("ORDER-1", 3, STANDARD, T0);

            assertEquals(7, state.available(T0));
        }

        @Test
        void sameOrderDifferentQuantityThrows() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 3, STANDARD, T0);

            assertThrows(IllegalArgumentException.class, () -> state.reserve("ORDER-1", 4, STANDARD, T0));
        }

        @Test
        void confirmedOrderCannotReserveAgain() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 3, STANDARD, T0);
            state.confirm("ORDER-1", T0);

            assertThrows(IllegalStateException.class, () -> state.reserve("ORDER-1", 3, STANDARD, T0));
        }

        @Test
        void expiredOrderCannotReserveAgain() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 3, STANDARD, T0);
            Instant afterExpiry = T0.plus(STANDARD.reservationTtl()).plusSeconds(1);

            assertThrows(IllegalStateException.class, () -> state.reserve("ORDER-1", 3, STANDARD, afterExpiry));
        }
    }

    @Nested
    class CategoryRules {

        @Test
        void standardExpiresAfter15Minutes() {
            ProductState state = new ProductState("SKU-1", ProductCategory.STANDARD);
            state.addStock(10, T0);

            Reservation reservation = state.reserve("ORDER-1", 1, STANDARD, T0).reservation();

            assertEquals(T0.plus(Duration.ofMinutes(15)), reservation.expiresAt());
        }

        @Test
        void preOrderExpiresAfter24Hours() {
            ProductState state = new ProductState("SKU-1", ProductCategory.PRE_ORDER);
            state.addStock(10, T0);

            Reservation reservation = state.reserve("ORDER-1", 1, PRE_ORDER, T0).reservation();

            assertEquals(T0.plus(Duration.ofHours(24)), reservation.expiresAt());
        }

        @Test
        void flashSaleExpiresAfter5Minutes() {
            ProductState state = new ProductState("SKU-1", ProductCategory.FLASH_SALE);
            state.addStock(10, T0);

            Reservation reservation = state.reserve("ORDER-1", 1, FLASH_SALE, T0).reservation();

            assertEquals(T0.plus(Duration.ofMinutes(5)), reservation.expiresAt());
        }

        @Test
        void flashSaleAllows2Units() {
            ProductState state = new ProductState("SKU-1", ProductCategory.FLASH_SALE);
            state.addStock(10, T0);

            Reservation reservation = state.reserve("ORDER-1", 2, FLASH_SALE, T0).reservation();

            assertEquals(2, reservation.quantity());
        }

        @Test
        void flashSaleRejects3Units() {
            ProductState state = new ProductState("SKU-1", ProductCategory.FLASH_SALE);
            state.addStock(10, T0);

            assertThrows(OrderLimitExceededException.class, () -> state.reserve("ORDER-1", 3, FLASH_SALE, T0));
        }
    }

    @Nested
    class Expiration {

        private final ProductState state = new ProductState("SKU-1", ProductCategory.FLASH_SALE);

        @Test
        void expirationRestoresAvailable() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 2, FLASH_SALE, T0);
            Instant afterExpiry = T0.plus(FLASH_SALE.reservationTtl()).plusSeconds(1);

            assertEquals(10, state.available(afterExpiry));
        }

        @Test
        void nowEqualsExpiresAtIsExpired() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 2, FLASH_SALE, T0);
            Instant exactlyAtExpiry = T0.plus(FLASH_SALE.reservationTtl());

            assertEquals(10, state.available(exactlyAtExpiry));
        }

        @Test
        void expiredReservationCannotConfirm() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 2, FLASH_SALE, T0);
            Instant afterExpiry = T0.plus(FLASH_SALE.reservationTtl());

            assertThrows(IllegalStateException.class, () -> state.confirm("ORDER-1", afterExpiry));
        }
    }

    @Nested
    class LowStock {

        private final ProductState state = new ProductState("SKU-1", ProductCategory.STANDARD);

        @Test
        void raisesLowStockAlert() {
            state.addStock(10, T0);

            boolean shouldAlert = state.reserve("ORDER-1", 6, STANDARD, T0).shouldAlert();

            assertTrue(shouldAlert);
        }

        @Test
        void doesNotRepeatLowStockAlert() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 6, STANDARD, T0);

            boolean shouldAlert = state.reserve("ORDER-2", 1, STANDARD, T0).shouldAlert();

            assertFalse(shouldAlert);
        }

        @Test
        void addStockRearmsLowStockAlert() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 6, STANDARD, T0);

            boolean shouldAlert = state.addStock(20, T0).shouldAlert();

            assertFalse(shouldAlert);
        }

        @Test
        void rearmAllowsLowStockAgain() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 6, STANDARD, T0);
            state.addStock(20, T0);

            boolean shouldAlert = state.reserve("ORDER-2", 20, STANDARD, T0).shouldAlert();

            assertTrue(shouldAlert);
        }

        @Test
        void expirationDoesNotRearmLowStockAlert() {
            state.addStock(10, T0);
            state.reserve("ORDER-1", 6, STANDARD, T0);
            Instant afterExpiry = T0.plus(STANDARD.reservationTtl()).plusSeconds(1);
            state.available(afterExpiry);

            boolean shouldAlert = state.reserve("ORDER-2", 6, STANDARD, afterExpiry).shouldAlert();

            assertFalse(shouldAlert);
        }
    }
}
