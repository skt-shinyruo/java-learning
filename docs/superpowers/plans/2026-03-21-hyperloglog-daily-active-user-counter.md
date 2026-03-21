# HyperLogLog Daily Active User Counter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a thin DailyActiveUserCounter practice on top of `HyperLogLog<Long>` to demonstrate approximate single-day UV counting in business language.

**Architecture:** Keep the example in `yier.bubu.redis.hyperloglog` so the generic sketch and its business-facing practice live in the same topic package. `DailyActiveUserCounter` will delegate to one internal `HyperLogLog<Long>` instance and expose a minimal API for `recordVisit`, `estimateDailyActiveUsers`, and `merge`. Tests will mirror the BloomFilter business example style: business language first, algorithm details implicit.

**Tech Stack:** Java 8, Maven, JUnit 4, existing `HyperLogLog<T>` implementation

---

## File Structure

- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java`
  Responsibility: business-facing wrapper around `HyperLogLog<Long>` for daily UV estimation.
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java`
  Responsibility: executable documentation for UV estimation, repeated visits, and shard merge.
- Modify: `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
  Responsibility: document the new DailyActiveUserCounter practice alongside HyperLogLog.

## Preflight

- Use TDD for the new practice: write the business-facing tests first, run them red, then add the minimal implementation.
- Reuse the existing HyperLogLog tolerance style instead of inventing a new accuracy contract.
- Keep the wrapper thin; do not add date management or tracker maps.

### Task 1: Add the Failing DailyActiveUserCounter Tests

**Files:**
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java`
- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java`

- [ ] **Step 1: Write the first failing business-facing tests**

```java
package yier.bubu.redis.hyperloglog;

import org.junit.Assert;
import org.junit.Test;

public class DailyActiveUserCounterTest {
    @Test
    public void counter_shouldEstimateKnownVisitorsInBusinessLanguage() {
        DailyActiveUserCounter counter = new DailyActiveUserCounter(10);

        for (long userId = 1L; userId <= 5000L; userId++) {
            counter.recordVisit(userId);
        }

        assertEstimateWithinWindow(counter.estimateDailyActiveUsers(), 5000L, 0.10D);
    }

    @Test
    public void counter_shouldNotInflateForRepeatedVisitsFromSameUser() {
        DailyActiveUserCounter counter = new DailyActiveUserCounter(10);

        for (int i = 0; i < 500; i++) {
            counter.recordVisit(42L);
        }

        Assert.assertTrue(counter.estimateDailyActiveUsers() <= 2L);
    }

    @Test
    public void counter_shouldMergeShardLevelUvCounters() {
        DailyActiveUserCounter left = new DailyActiveUserCounter(10);
        DailyActiveUserCounter right = new DailyActiveUserCounter(10);

        for (long userId = 1L; userId <= 2500L; userId++) {
            left.recordVisit(userId);
        }
        for (long userId = 2501L; userId <= 5000L; userId++) {
            right.recordVisit(userId);
        }

        left.merge(right);

        assertEstimateWithinWindow(left.estimateDailyActiveUsers(), 5000L, 0.10D);
    }

    @Test
    public void counter_shouldRejectMergeAcrossDifferentPrecision() {
        final DailyActiveUserCounter coarse = new DailyActiveUserCounter(10);
        final DailyActiveUserCounter fine = new DailyActiveUserCounter(12);

        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                coarse.merge(fine);
            }
        });
    }
}
```

- [ ] **Step 2: Run the new DailyActiveUserCounter test class to verify it is red**

Run: `mvn -pl redis -am test -Dtest=DailyActiveUserCounterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `DailyActiveUserCounter` does not exist yet.

- [ ] **Step 3: Implement the smallest DailyActiveUserCounter wrapper**

```java
package yier.bubu.redis.hyperloglog;

public final class DailyActiveUserCounter {
    private final HyperLogLog<Long> sketch;

    public DailyActiveUserCounter(int precision) {
        this.sketch = new HyperLogLog<Long>(precision);
    }

    public void recordVisit(long userId) {
        sketch.add(userId);
    }

    public long estimateDailyActiveUsers() {
        return sketch.estimate();
    }

    public void merge(DailyActiveUserCounter other) {
        sketch.merge(other.sketch);
    }
}
```

Implementation notes:
- keep the class final and small
- delegate all algorithmic behavior to `HyperLogLog<Long>`
- let underlying precision mismatch logic surface through `merge`
- add only the null handling needed for `merge(other)`

- [ ] **Step 4: Re-run the DailyActiveUserCounter test class**

Run: `mvn -pl redis -am test -Dtest=DailyActiveUserCounterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for the four business-facing behaviors.

- [ ] **Step 5: Commit the DailyActiveUserCounter slice**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java
git commit -m "feat(redis): add daily active user counter example"
```

### Task 2: Update HyperLogLog Package Docs

**Files:**
- Modify: `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`

- [ ] **Step 1: Extend package docs to mention the business-facing UV example**

```java
/**
 * 学习向、单机内存版 HyperLogLog 示例。
 * <p>
 * 这个包当前包含：
 * <ul>
 *   <li>{@link yier.bubu.redis.hyperloglog.HyperLogLog}：近似 distinct 计数</li>
 *   <li>{@link yier.bubu.redis.hyperloglog.DailyActiveUserCounter}：用 HyperLogLog 做单日 UV 估计的业务化示例</li>
 * </ul>
 * <p>
 * 这里的实现刻意保持为“学习向、单节点、纯内存”版本，不涉及 Redis 网络协议、序列化或分布式同步。
 */
package yier.bubu.redis.hyperloglog;
```

- [ ] **Step 2: Run the new practice test again after the doc update**

Run: `mvn -pl redis -am test -Dtest=DailyActiveUserCounterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, confirming the doc-only change did not break compilation.

- [ ] **Step 3: Commit the package-doc update**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java
git commit -m "docs(redis): describe daily active user counter"
```

### Task 3: Final Verification

**Files:**
- Verify: `redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java`
- Verify: `redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java`
- Verify: `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`

- [ ] **Step 1: Run the focused HyperLogLog-topic tests together**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest,DailyActiveUserCounterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, both the generic sketch and the UV example green.

- [ ] **Step 2: Run the full redis module test suite**

Run: `mvn -pl redis -am test`

Expected: PASS, no regressions in BloomFilter or other redis learning examples.

- [ ] **Step 3: Run the full concurrency module test suite**

Run: `mvn -pl concurrency -am test`

Expected: PASS, no regressions in the remaining concurrency examples.

- [ ] **Step 4: Review final scope**

Run: `git diff --stat -- redis/src/main/java/yier/bubu/redis/hyperloglog redis/src/test/java/yier/bubu/redis/hyperloglog`

Expected: changes are limited to the new DAU example and package docs.

- [ ] **Step 5: If a verification step fails, fix the smallest issue and re-run the affected command**

Run: the previously failing Maven or git command

Expected: PASS after the minimal corrective change.

- [ ] **Step 6: Commit only if Step 5 changed tracked files**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java \
        redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java
git commit -m "test(redis): finalize daily active user counter example"
```
