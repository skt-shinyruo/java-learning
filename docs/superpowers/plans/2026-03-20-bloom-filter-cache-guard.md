# Bloom Filter Cache Guard Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JDK-only Bloom filter implementation and a cache-penetration guard example in the `concurrency` module, documented by focused JUnit tests.

**Architecture:** Introduce a new `yier.bubu.concurrency.cache` package. `BloomFilter<T>` owns parameter sizing, hashing, and membership checks over an in-memory `BitSet`, while `ProductCatalogExistenceGuard` wraps the filter in product-catalog business language so tests can show how invalid IDs get blocked before Redis or MySQL lookups.

**Tech Stack:** Java 8, Maven, JUnit 4, JDK `BitSet`, JDK collections

---

## File Structure

- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java`
  Responsibility: generic Bloom filter with constructor validation, formula-based sizing, `put`, `mightContain`, and lightweight read-only metadata accessors.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuard.java`
  Responsibility: thin business wrapper around `BloomFilter<Long>` for cache-penetration prevention semantics.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
  Responsibility: package-level documentation for cache-facing in-memory mechanisms and Bloom filter trade-offs.
- Create: `concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java`
  Responsibility: executable documentation for Bloom filter insert/query behavior, sizing, and argument validation.
- Create: `concurrency/src/test/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuardTest.java`
  Responsibility: executable documentation for the product-ID cache-penetration use case.

## Preflight

- Work in an isolated git worktree instead of `main`.
- Use TDD for each task: write the test first, run it to observe the red state, then write the minimal implementation.
- After each task, run only the affected test class first, then commit the isolated change.

### Task 1: Add the Generic Bloom Filter Core

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java`

- [ ] **Step 1: Write the failing Bloom filter tests**

```java
package yier.bubu.concurrency.cache;

import org.junit.Assert;
import org.junit.Test;

public class BloomFilterTest {
    @Test
    public void bloomFilter_shouldReportInsertedValuesAsPresent() {
        BloomFilter<String> filter = new BloomFilter<String>(100, 0.01);

        filter.put("sku-1001");
        filter.put("sku-1002");

        Assert.assertTrue(filter.mightContain("sku-1001"));
        Assert.assertTrue(filter.mightContain("sku-1002"));
    }

    @Test
    public void bloomFilter_shouldExposePositiveSizingMetadata() {
        BloomFilter<String> filter = new BloomFilter<String>(1_000, 0.01);

        Assert.assertEquals(1_000L, filter.expectedInsertions());
        Assert.assertEquals(0.01, filter.falsePositiveProbability(), 0.0);
        Assert.assertTrue(filter.bitSize() > 0);
        Assert.assertTrue(filter.hashFunctionCount() > 0);
    }
}
```

- [ ] **Step 2: Run the Bloom filter test class to verify it is red**

Run: `mvn -pl concurrency -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: test compilation fails because `BloomFilter` does not exist yet.

- [ ] **Step 3: Write the minimal Bloom filter implementation**

```java
package yier.bubu.concurrency.cache;

import java.util.BitSet;
import java.util.Objects;

public final class BloomFilter<T> {
    private static final double LN_2 = Math.log(2.0D);

    private final long expectedInsertions;
    private final double falsePositiveProbability;
    private final int bitSize;
    private final int hashFunctionCount;
    private final BitSet bits;

    public BloomFilter(long expectedInsertions, double falsePositiveProbability) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0");
        }
        if (!(falsePositiveProbability > 0.0D && falsePositiveProbability < 1.0D)) {
            throw new IllegalArgumentException("falsePositiveProbability must be in (0, 1)");
        }
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveProbability = falsePositiveProbability;
        this.bitSize = optimalBitSize(expectedInsertions, falsePositiveProbability);
        this.hashFunctionCount = optimalHashFunctionCount(expectedInsertions, bitSize);
        this.bits = new BitSet(bitSize);
    }

    public void put(T value) {
        for (int index : indexesFor(value)) {
            bits.set(index);
        }
    }

    public boolean mightContain(T value) {
        for (int index : indexesFor(value)) {
            if (!bits.get(index)) {
                return false;
            }
        }
        return true;
    }

    public long expectedInsertions() {
        return expectedInsertions;
    }

    public double falsePositiveProbability() {
        return falsePositiveProbability;
    }

    public int bitSize() {
        return bitSize;
    }

    public int hashFunctionCount() {
        return hashFunctionCount;
    }

    private int[] indexesFor(T value) {
        Objects.requireNonNull(value, "value");
        int[] indexes = new int[hashFunctionCount];
        int hash1 = smear(value.hashCode());
        int hash2 = smear(hash1 ^ 0x5bd1e995);
        for (int i = 0; i < hashFunctionCount; i++) {
            long combined = (hash1 & 0xffffffffL) + (long) i * (hash2 & 0xffffffffL);
            indexes[i] = (int) (Math.floorMod(combined, bitSize));
        }
        return indexes;
    }

    static int optimalBitSize(long expectedInsertions, double falsePositiveProbability) {
        return (int) Math.ceil((-expectedInsertions * Math.log(falsePositiveProbability)) / (LN_2 * LN_2));
    }

    static int optimalHashFunctionCount(long expectedInsertions, int bitSize) {
        return Math.max(1, (int) Math.round((bitSize / (double) expectedInsertions) * LN_2));
    }

    private static int smear(int hash) {
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash;
    }
}
```

- [ ] **Step 4: Run the Bloom filter tests to verify they are green**

Run: `mvn -pl concurrency -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, `BloomFilterTest` green.

- [ ] **Step 5: Commit the generic Bloom filter core**

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java \
        concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java
git commit -m "feat(concurrency): add bloom filter core"
```

### Task 2: Tighten Bloom Filter Validation and Sizing Coverage

**Files:**
- Modify: `concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java`

- [ ] **Step 1: Extend the Bloom filter test with validation and sizing expectations**

```java
@Test
public void bloomFilter_shouldRejectInvalidConstructorArguments() {
    assertIllegalArgument(new ThrowingRunnable() {
        @Override
        public void run() {
            new BloomFilter<String>(0, 0.01);
        }
    });
    assertIllegalArgument(new ThrowingRunnable() {
        @Override
        public void run() {
            new BloomFilter<String>(10, 1.0);
        }
    });
}

@Test
public void bloomFilter_shouldUseMoreBitsForLowerFalsePositiveProbability() {
    BloomFilter<String> loose = new BloomFilter<String>(1_000, 0.05);
    BloomFilter<String> strict = new BloomFilter<String>(1_000, 0.001);

    Assert.assertTrue(strict.bitSize() > loose.bitSize());
}

@Test
public void bloomFilter_shouldRejectNullValues() {
    final BloomFilter<String> filter = new BloomFilter<String>(100, 0.01);

    assertNullPointer(new ThrowingRunnable() {
        @Override
        public void run() {
            filter.put(null);
        }
    });
}

private interface ThrowingRunnable {
    void run();
}

private static void assertIllegalArgument(ThrowingRunnable runnable) {
    try {
        runnable.run();
        Assert.fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
        // expected
    }
}

private static void assertNullPointer(ThrowingRunnable runnable) {
    try {
        runnable.run();
        Assert.fail("expected NullPointerException");
    } catch (NullPointerException expected) {
        // expected
    }
}
```

- [ ] **Step 2: Run the Bloom filter test class to verify it is red for the new behavior**

Run: `mvn -pl concurrency -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL due to missing validation behavior or missing helper methods in the test.

- [ ] **Step 3: Implement the smallest changes needed for validation and sizing**

```java
private static void validateFalsePositiveProbability(double value) {
    if (!(value > 0.0D && value < 1.0D)) {
        throw new IllegalArgumentException("falsePositiveProbability must be in (0, 1)");
    }
}
```

Implementation notes:
- keep constructor validation messages explicit
- if needed, extract small package-private helpers for formula calculations
- do not add deletion, counting support, or serialization

- [ ] **Step 4: Re-run the Bloom filter tests**

Run: `mvn -pl concurrency -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, including validation and sizing assertions.

- [ ] **Step 5: Commit the Bloom filter validation pass**

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java \
        concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java
git commit -m "test(concurrency): document bloom filter sizing"
```

### Task 3: Add the Cache-Penetration Guard Example

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuardTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuard.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`

- [ ] **Step 1: Write the failing cache-guard tests**

```java
package yier.bubu.concurrency.cache;

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
```

- [ ] **Step 2: Run the cache-guard test class to verify it is red**

Run: `mvn -pl concurrency -am test -Dtest=ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: test compilation fails because `ProductCatalogExistenceGuard` does not exist yet.

- [ ] **Step 3: Implement the cache guard and package documentation**

```java
package yier.bubu.concurrency.cache;

import java.util.Collection;
import java.util.Objects;

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
```

Package documentation should explain:
- why Bloom filters are useful against cache penetration
- why `false` means "definitely absent" but `true` only means "might exist"
- that this package is single-node and learning-oriented

- [ ] **Step 4: Run the cache-guard tests**

Run: `mvn -pl concurrency -am test -Dtest=ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, with the chosen example data blocking at least one obviously absent product ID.

- [ ] **Step 5: Commit the business example**

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java \
        concurrency/src/main/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuard.java \
        concurrency/src/test/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuardTest.java
git commit -m "feat(concurrency): add bloom filter cache guard example"
```

### Task 4: Run Module-Level Verification

**Files:**
- Verify: `concurrency/src/main/java/yier/bubu/concurrency/cache/BloomFilter.java`
- Verify: `concurrency/src/main/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuard.java`
- Verify: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
- Verify: `concurrency/src/test/java/yier/bubu/concurrency/cache/BloomFilterTest.java`
- Verify: `concurrency/src/test/java/yier/bubu/concurrency/cache/ProductCatalogExistenceGuardTest.java`

- [ ] **Step 1: Run the new cache package test classes together**

Run: `mvn -pl concurrency -am test -Dtest=BloomFilterTest,ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, both new test classes green.

- [ ] **Step 2: Run the full concurrency module test suite**

Run: `mvn -pl concurrency -am test`

Expected: PASS, no regressions in existing concurrency demos.

- [ ] **Step 3: Review the final diff for scope control**

Run: `git diff --stat -- concurrency/src/main/java/yier/bubu/concurrency/cache concurrency/src/test/java/yier/bubu/concurrency/cache`

Expected: changes are limited to the new cache package plus tests and no unrelated files.

- [ ] **Step 4: If verification uncovers failures, fix the smallest issue and re-run the affected command**

Run: the previously failing Maven command

Expected: PASS after the minimal fix.

- [ ] **Step 5: Commit only if Step 4 changed tracked files**

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/cache \
        concurrency/src/test/java/yier/bubu/concurrency/cache
git commit -m "fix(concurrency): finalize bloom filter cache package"
```
