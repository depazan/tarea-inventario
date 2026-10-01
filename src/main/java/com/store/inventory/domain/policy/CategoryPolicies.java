package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;

/**
 * Stores the reservation rules for the product categories
 * supported by the public API.
 */
public final class CategoryPolicies {

    private static final Map<ProductCategory, CategoryPolicy> POLICIES = Map.of(
            ProductCategory.STANDARD, new CategoryPolicy(Duration.ofMinutes(15), CategoryPolicy.UNLIMITED),
            ProductCategory.PRE_ORDER, new CategoryPolicy(Duration.ofHours(24), CategoryPolicy.UNLIMITED),
            ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2));

    private CategoryPolicies() {
    }

    /**
     * @param category category to look up
     * @return the configured policy for {@code category}
     * @throws IllegalStateException if {@code category} has no configured policy; only
     *         possible if {@code ProductCategory} gains a value that isn't yet listed above
     */
    public static CategoryPolicy of(ProductCategory category) {
        CategoryPolicy policy = POLICIES.get(category);
        if (policy == null) {
            throw new IllegalStateException("No reservation policy configured for category " + category);
        }
        return policy;
    }
}
