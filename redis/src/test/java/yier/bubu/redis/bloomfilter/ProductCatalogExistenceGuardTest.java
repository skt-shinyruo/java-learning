package yier.bubu.redis.bloomfilter;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ProductCatalogExistenceGuardTest {
    @Test
    public void guard_shouldAllowKnownProductIdsToReachBackend() {
        ProductCatalogExistenceGuard guard =
                new ProductCatalogExistenceGuard(Arrays.asList(1001L, 1002L, 1003L), 0.0001D);

        Assert.assertTrue(guard.shouldQueryBackend(1002L));
    }

    @Test
    public void guard_shouldBlockSomeClearlyUnknownProductIdsInThisExample() {
        ProductCatalogExistenceGuard guard =
                new ProductCatalogExistenceGuard(Arrays.asList(1001L, 1002L, 1003L), 0.0001D);

        int blocked = 0;
        for (long candidate = 900000L; candidate < 900100L; candidate++) {
            if (!guard.shouldQueryBackend(candidate)) {
                blocked++;
            }
        }

        Assert.assertTrue("示例配置下，应至少拦住一个不存在商品 ID", blocked > 0);
    }

    @Test
    public void guard_shouldAdmitNewlyAddedProducts() {
        ProductCatalogExistenceGuard guard =
                new ProductCatalogExistenceGuard(Arrays.asList(1001L, 1002L, 1003L), 0.0001D);

        guard.addProduct(2001L);

        Assert.assertTrue(guard.shouldQueryBackend(2001L));
    }
}
