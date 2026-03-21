# HyperLogLog Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JDK-only, learning-oriented `HyperLogLog<T>` implementation to the `concurrency` module's dedicated `cardinality` package with injectable 64-bit hashing, focused JUnit tests, and matching documentation updates.

**Architecture:** Introduce `HyperLogLog<T>` in `yier.bubu.concurrency.cardinality`. The sketch will store fixed-width register state derived from `precision`, support incremental `add`, approximate `estimate`, and in-place `merge`, and will use a `ToLongFunction<? super T>` overload so tests and callers can supply a true 64-bit hash source when needed. Tests will pin cardinality assertions to `p = 10` and fixed deterministic datasets so the behavior stays stable and instructional.

**Tech Stack:** Java 8, Maven, JUnit 4, JDK collections and math utilities

---

## File Structure

- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
  Responsibility: generic in-memory HyperLogLog sketch with constructor validation, add/update logic, cardinality estimation, merge support, and lightweight metadata accessors.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`
  Responsibility: package-level documentation for approximate cardinality estimation examples centered on HyperLogLog.
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
  Responsibility: keep cache package docs focused on BloomFilter and ProductCatalogExistenceGuard after moving HyperLogLog out.
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
  Responsibility: executable documentation for validation, metadata, injected hashing, estimation, duplicate handling, and merge behavior.
- Modify: `redis/docs/hyperloglog.md`
  Responsibility: update the document so it no longer claims the repository lacks an implementation and point readers to the new code location.

## Preflight

- Work in the current branch/workspace without reverting unrelated changes.
- Use TDD for each behavior slice: write the failing test, run it to verify the red state, then write the minimal implementation.
- Run the focused `HyperLogLogTest` class after each green step before moving on.

### Task 1: Add the Failing HyperLogLog Validation and Metadata Tests

**Files:**
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`

- [ ] **Step 1: Write the first failing tests for constructor validation and metadata**

```java
package yier.bubu.concurrency.cardinality;

import org.junit.Assert;
import org.junit.Test;

public class HyperLogLogTest {
    @Test
    public void hyperLogLog_shouldRejectPrecisionBelowSupportedRange() {
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new HyperLogLog<Long>(3);
            }
        });
    }

    @Test
    public void hyperLogLog_shouldRejectPrecisionAboveSupportedRange() {
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new HyperLogLog<Long>(19);
            }
        });
    }

    @Test
    public void hyperLogLog_shouldExposeMetadataForPrecisionTen() {
        HyperLogLog<Long> sketch = new HyperLogLog<Long>(10);

        Assert.assertEquals(10, sketch.precision());
        Assert.assertEquals(1024, sketch.registerCount());
        Assert.assertEquals(1.04D / Math.sqrt(1024.0D), sketch.standardError(), 0.0000001D);
    }
}
```

- [ ] **Step 2: Run the new HyperLogLog test class to verify it is red**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `HyperLogLog` and its metadata methods do not exist yet.

- [ ] **Step 3: Implement the smallest HyperLogLog skeleton that makes the metadata tests pass**

```java
public final class HyperLogLog<T> {
    private static final int MIN_PRECISION = 4;
    private static final int MAX_PRECISION = 18;

    private final int precision;
    private final int registerCount;

    public HyperLogLog(int precision) {
        this(precision, defaultHashFunction());
    }

    public HyperLogLog(int precision, ToLongFunction<? super T> hashFunction) {
        validatePrecision(precision);
        this.precision = precision;
        this.registerCount = 1 << precision;
    }

    public int precision() {
        return precision;
    }

    public int registerCount() {
        return registerCount;
    }

    public double standardError() {
        return 1.04D / Math.sqrt(registerCount);
    }
}
```

Implementation notes:
- add constructor validation with explicit `IllegalArgumentException` messages
- add placeholder fields for future register storage and hash function injection, even if the first green step does not use them yet
- define the convenience default hash supplier as “take `value.hashCode()`, widen to `long`, then apply a
  SplitMix64-style mixing sequence” so the no-arg constructor still follows the spec's 64-bit path
- do not implement `add`, `estimate`, or `merge` beyond what is needed for compilation in this task

- [ ] **Step 4: Re-run the HyperLogLog test class**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for the validation and metadata tests.

- [ ] **Step 5: Commit the validation and metadata slice**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java
git commit -m "test(concurrency): add hyperloglog metadata tests"
```

### Task 2: Add Failing Tests for Injected Hashing and Duplicate Handling

**Files:**
- Modify: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Modify: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`

- [ ] **Step 1: Extend the test class with custom-hash and duplicate-behavior tests**

```java
@Test
public void hyperLogLog_shouldUseInjectedHashFunction() {
    HyperLogLog<String> sketch = new HyperLogLog<String>(10, new ToLongFunction<String>() {
        @Override
        public long applyAsLong(String value) {
            return 0x123456789ABCDEFL;
        }
    });

    sketch.add("alpha");
    sketch.add("beta");

    Assert.assertEquals(1L, sketch.estimate());
}

@Test
public void hyperLogLog_shouldNotInflateEstimateForRepeatedValues() {
    HyperLogLog<Long> sketch = new HyperLogLog<Long>(10, LONG_MIX_64);

    for (int i = 0; i < 500; i++) {
        sketch.add(42L);
    }

    Assert.assertTrue(sketch.estimate() <= 2L);
}
```

Add a deterministic 64-bit helper in the test:

```java
private static final ToLongFunction<Long> LONG_MIX_64 = new ToLongFunction<Long>() {
    @Override
    public long applyAsLong(Long value) {
        long x = value.longValue() + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
};
```

- [ ] **Step 2: Run the HyperLogLog test class again to verify the new tests are red**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `add` and `estimate` are not implemented or do not yet use the injected hash path.

- [ ] **Step 3: Implement register storage, injected hashing, and minimal add/estimate support**

```java
private final ToLongFunction<? super T> hashFunction;
private final byte[] registers;

public void add(T value) {
    Objects.requireNonNull(value, "value");
    long hash = hashFunction.applyAsLong(value);
    int index = (int) (hash >>> (Long.SIZE - precision));
    int rank = rank(hash);
    if (rank > registers[index]) {
        registers[index] = (byte) rank;
    }
}

public long estimate() {
    double sum = 0.0D;
    int zeroCount = 0;
    for (byte register : registers) {
        sum += Math.scalb(1.0D, -register);
        if (register == 0) {
            zeroCount++;
        }
    }
    double estimate = alpha(registerCount) * registerCount * registerCount / sum;
    if (estimate <= 2.5D * registerCount && zeroCount > 0) {
        estimate = registerCount * Math.log(registerCount / (double) zeroCount);
    }
    return Math.round(estimate);
}
```

Implementation notes:
- store register state in a `byte[]`
- compute `rank` from the post-index bits and cap the all-zero remainder case correctly
- wire the no-arg constructor through a default hash-function supplier such as:

```java
private static <T> ToLongFunction<T> defaultHashFunction() {
    return new ToLongFunction<T>() {
        @Override
        public long applyAsLong(T value) {
            long x = value.hashCode();
            x += 0x9E3779B97F4A7C15L;
            x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
            x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
            return x ^ (x >>> 31);
        }
    };
}
```

- keep the implementation learning-oriented; do not add sparse mode or bias tables

- [ ] **Step 4: Re-run the HyperLogLog test class**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for validation, metadata, injected hashing, and duplicate-handling tests.

- [ ] **Step 5: Commit the add/estimate foundation**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java
git commit -m "feat(concurrency): add hyperloglog core"
```

### Task 3: Add Failing Cardinality and Merge Tests

**Files:**
- Modify: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Modify: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`

- [ ] **Step 1: Add deterministic cardinality and merge tests pinned to precision ten**

```java
@Test
public void hyperLogLog_shouldEstimateFiveThousandDistinctValuesWithinTenPercentAtPrecisionTen() {
    HyperLogLog<Long> sketch = new HyperLogLog<Long>(10, LONG_MIX_64);

    for (long value = 1L; value <= 5000L; value++) {
        sketch.add(value);
    }

    assertEstimateWithinWindow(sketch.estimate(), 5000L, 0.10D);
}

@Test
public void hyperLogLog_shouldMergeTwoSketchesAsApproximateUnion() {
    HyperLogLog<Long> left = new HyperLogLog<Long>(10, LONG_MIX_64);
    HyperLogLog<Long> right = new HyperLogLog<Long>(10, LONG_MIX_64);

    for (long value = 1L; value <= 2500L; value++) {
        left.add(value);
    }
    for (long value = 2501L; value <= 5000L; value++) {
        right.add(value);
    }

    left.merge(right);

    assertEstimateWithinWindow(left.estimate(), 5000L, 0.10D);
}

@Test
public void hyperLogLog_shouldRejectMergeAcrossDifferentPrecision() {
    HyperLogLog<Long> coarse = new HyperLogLog<Long>(10, LONG_MIX_64);
    HyperLogLog<Long> fine = new HyperLogLog<Long>(12, LONG_MIX_64);

    assertIllegalArgument(new ThrowingRunnable() {
        @Override
        public void run() {
            coarse.merge(fine);
        }
    });
}
```

Add a helper:

```java
private static void assertEstimateWithinWindow(long actual, long expected, double tolerance) {
    long delta = Math.round(expected * tolerance);
    Assert.assertTrue(actual >= expected - delta);
    Assert.assertTrue(actual <= expected + delta);
}
```

- [ ] **Step 2: Run the HyperLogLog test class to verify the new behavior is red**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because merge is not implemented or the cardinality estimate is not yet correct enough.

- [ ] **Step 3: Implement merge and refine the estimator**

```java
public void merge(HyperLogLog<?> other) {
    Objects.requireNonNull(other, "other");
    if (precision != other.precision) {
        throw new IllegalArgumentException("precision must match for merge");
    }
    for (int i = 0; i < registers.length; i++) {
        if (other.registers[i] > registers[i]) {
            registers[i] = other.registers[i];
        }
    }
}

private static double alpha(int registerCount) {
    switch (registerCount) {
        case 16:
            return 0.673D;
        case 32:
            return 0.697D;
        case 64:
            return 0.709D;
        default:
            return 0.7213D / (1.0D + 1.079D / registerCount);
    }
}
```

Implementation notes:
- make `merge` in-place and linear in register count
- keep merge compatibility based on matching `precision`
- if the targeted `p = 10` estimate test misses the ±10% window, fix the rank calculation before widening the tests

- [ ] **Step 4: Re-run the HyperLogLog test class**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for validation, injected hashing, duplicates, estimate accuracy, and merge behavior.

- [ ] **Step 5: Commit the merge and estimation slice**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java
git commit -m "feat(concurrency): support hyperloglog merge"
```

### Task 4: Update Package and Concept Docs

**Files:**
- Create: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
- Modify: `redis/docs/hyperloglog.md`

- [ ] **Step 1: Add cardinality package docs and restore cache package docs**

```java
/**
 * 学习向、单机内存版基数估计示例。
 * <p>
 * 这个包当前包含：
 * <ul>
 *   <li>{@link yier.bubu.redis.hyperloglog.HyperLogLog}：近似 distinct 计数</li>
 * </ul>
 * <p>
 * 这里的实现刻意保持为“学习向、单节点、纯内存”版本，不涉及 Redis、序列化或分布式同步。
 */
package yier.bubu.concurrency.cardinality;
```

- [ ] **Step 2: Update the HyperLogLog concept document to reference the implementation**

Edit `redis/docs/hyperloglog.md` so the opening section says the repository now contains a
learning-oriented implementation at
`redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`, while the document
continues to focus on concepts and formulas.

- [ ] **Step 3: Review the documentation diff for scope control**

Run: `git diff -- concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java redis/docs/hyperloglog.md`

Expected: the diff only broadens package wording and replaces the "no implementation yet" claim
with a short implementation reference.

- [ ] **Step 4: Commit the documentation update**

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java \
        redis/docs/hyperloglog.md
git commit -m "docs(concurrency): document hyperloglog implementation"
```

### Task 5: Final Verification

**Files:**
- Verify: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- Verify: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`
- Verify: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
- Verify: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Verify: `redis/docs/hyperloglog.md`

- [ ] **Step 1: Run the HyperLogLog test class one more time**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, all HyperLogLog-specific behaviors green.

- [ ] **Step 2: Run the full concurrency module test suite**

Run: `mvn -pl concurrency -am test`

Expected: PASS, no regressions in the existing concurrency examples.

- [ ] **Step 3: Review the final diff stat**

Run: `git diff --stat -- concurrency/src/main/java/yier/bubu/concurrency/cache concurrency/src/test/java/yier/bubu/concurrency/cache redis/docs/hyperloglog.md`

Expected: changes are limited to the HyperLogLog class, its test, package docs, and the concept doc.

- [ ] **Step 4: If any verification command fails, fix the smallest issue and re-run the affected command**

Run: the previously failing Maven or git command

Expected: PASS after the minimal corrective change.

- [ ] **Step 5: Commit only if Step 4 changed tracked files**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java \
        concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java \
        concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java \
        redis/docs/hyperloglog.md
git commit -m "test(concurrency): finalize hyperloglog implementation"
```
