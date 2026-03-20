package yier.bubu.concurrency.cache;

import java.util.Collection;
import java.util.Objects;

/**
 * 商品目录存在性保护层：
 * 用布隆过滤器在入口快速拦截“明显不存在”的商品 ID，减少无效后端查询。
 */
public final class ProductCatalogExistenceGuard {
    private final BloomFilter<Long> bloomFilter;

    public ProductCatalogExistenceGuard(Collection<Long> existingProductIds, double falsePositiveProbability) {
        Objects.requireNonNull(existingProductIds, "existingProductIds");
        if (existingProductIds.isEmpty()) {
            throw new IllegalArgumentException("existingProductIds must not be empty");
        }

        this.bloomFilter = new BloomFilter<Long>(existingProductIds.size(), falsePositiveProbability);
        for (Long existingProductId : existingProductIds) {
            bloomFilter.put(existingProductId);
        }
    }

    public boolean shouldQueryBackend(long productId) {
        return bloomFilter.mightContain(productId);
    }

    public void addProduct(long productId) {
        bloomFilter.put(productId);
    }
}
