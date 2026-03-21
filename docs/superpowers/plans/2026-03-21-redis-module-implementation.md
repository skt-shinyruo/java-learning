# Redis Module Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated `redis` Maven module and move BloomFilter, HyperLogLog, and ProductCatalogExistenceGuard plus their tests and docs out of `concurrency`.

**Architecture:** Create a new top-level `redis` module under the root aggregator POM. Inside that module, organize code by topic: `yier.bubu.redis.hyperloglog` for HyperLogLog-related content and `yier.bubu.redis.bloomfilter` for BloomFilter-related content, including the cache-penetration example built on BloomFilter. Migrate the existing code and tests with minimal behavioral change, then update module/package docs and implementation references so `concurrency` no longer owns these Redis-adjacent examples.

**Tech Stack:** Java 8, Maven multi-module build, JUnit 4, JDK collections and math utilities

---

## File Structure

- Modify: `pom.xml`
  Responsibility: add the new `redis` module to the root reactor.
- Create: `redis/pom.xml`
  Responsibility: define the new Maven module with `base` and `junit` dependencies.
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
  Responsibility: host the moved Bloom filter implementation under the new module/package.
- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
  Responsibility: host the moved HyperLogLog implementation under the new module/package.
- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
  Responsibility: describe HyperLogLog-related content in the new module.
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
  Responsibility: host the BloomFilter-based cache-penetration example under the new topic package.
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`
  Responsibility: describe BloomFilter-related content in the new module.
- Create: `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
  Responsibility: verify Bloom filter behavior after the move.
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
  Responsibility: verify HyperLogLog behavior after the move.
- Create: `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`
  Responsibility: verify the cache-penetration example after the move.
- Modify or delete: `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- Modify or delete: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java`
- Modify or delete: `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- Modify or delete: `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- Modify or delete: `concurrency/src/test/java/yier/bubu/concurrency/cardinality/HyperLogLogTest.java`
- Modify or delete: `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`
- Modify or delete: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`
- Modify or delete: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`
  Responsibility: clean up stale package ownership after the move.
- Modify: `redis/docs/hyperloglog.md`
- Modify: `docs/superpowers/specs/2026-03-21-hyperloglog-design.md`
- Modify: `docs/superpowers/plans/2026-03-21-hyperloglog-implementation.md`
- Modify as needed: Bloom-filter-related spec/plan docs that still point to `concurrency`
  Responsibility: update implementation references to the new `redis` module paths.

## Preflight

- Keep all existing in-progress workspace changes; do not revert unrelated edits.
- Use TDD where behavior is being preserved: first move tests into the new module and verify red where appropriate, then move the implementation until green.
- Prefer moving existing code with the smallest structural change instead of rewriting these classes.

### Task 1: Add the New Redis Maven Module Skeleton

**Files:**
- Modify: `pom.xml`
- Create: `redis/pom.xml`

- [ ] **Step 1: Write the failing module skeleton by adding `redis` to the root reactor and creating its POM**

```xml
<modules>
  <module>base</module>
  <module>jdk</module>
  <module>jvm</module>
  <module>netty</module>
  <module>concurrency</module>
  <module>redis</module>
</modules>
```

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>yier.bubu</groupId>
    <artifactId>java-learning</artifactId>
    <version>1.0-SNAPSHOT</version>
  </parent>

  <artifactId>redis</artifactId>
  <name>redis</name>

  <dependencies>
    <dependency>
      <groupId>yier.bubu</groupId>
      <artifactId>base</artifactId>
      <version>${project.version}</version>
    </dependency>

    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 2: Run Maven on the empty redis module to verify the new reactor configuration is green**

Run: `mvn -pl redis -am test`

Expected: PASS with no tests, confirming the new module is wired into the reactor.

- [ ] **Step 3: Commit the module skeleton**

```bash
git add pom.xml redis/pom.xml
git commit -m "build: add redis module"
```

### Task 2: Move BloomFilter Content Into Redis BloomFilter Package

**Files:**
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- Create: `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- Delete: `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- Delete: `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`

- [ ] **Step 1: Create the moved BloomFilter test in the new module/package**

Use the existing test content as the starting point, with only the package changed to:

```java
package yier.bubu.redis.hyperloglog;
```

- [ ] **Step 2: Run the redis BloomFilter test to verify it is red before moving implementation**

Run: `mvn -pl redis -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `BloomFilter` is not yet present in the new module/package.

- [ ] **Step 3: Move the BloomFilter implementation into the new module/package**

Move the existing class with the package changed to:

```java
package yier.bubu.redis.hyperloglog;
```

Keep:
- constructor validation
- `put`
- `mightContain`
- metadata accessors
- current hashing approach

- [ ] **Step 4: Remove the old concurrency BloomFilter source and test**

Delete:
- `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`

- [ ] **Step 5: Re-run the redis BloomFilter test**

Run: `mvn -pl redis -am test -Dtest=BloomFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS in the new module.

- [ ] **Step 6: Commit the BloomFilter move**

```bash
git add redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java \
        redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java \
        redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java \
        redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java
git commit -m "refactor(redis): move bloom filter into redis module"
```

### Task 3: Move HyperLogLog Content Into Redis HyperLogLog Package

**Files:**
- Create: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- Create: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/cardinality/HyperLogLogTest.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`

- [ ] **Step 1: Create the moved HyperLogLog test in the new module/package**

Use the existing test content as the starting point, with only the package changed to:

```java
package yier.bubu.redis.hyperloglog;
```

- [ ] **Step 2: Run the redis HyperLogLog test to verify it is red before moving implementation**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `HyperLogLog` is not yet present in the new module/package.

- [ ] **Step 3: Move the HyperLogLog implementation into the new module/package**

Move the existing class with the package changed to:

```java
package yier.bubu.redis.hyperloglog;
```

Keep:
- both constructors
- `add`, `estimate`, and `merge`
- precision validation
- default hash mixing path
- the current estimator and merge semantics

- [ ] **Step 4: Remove the old concurrency HyperLogLog source, test, and package-info**

Delete:
- `concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java`
- `concurrency/src/test/java/yier/bubu/concurrency/cardinality/HyperLogLogTest.java`
- `concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java`

- [ ] **Step 5: Re-run the redis HyperLogLog test**

Run: `mvn -pl redis -am test -Dtest=HyperLogLogTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS in the new module.

- [ ] **Step 6: Commit the HyperLogLog move**

```bash
git add redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java \
        redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java \
        concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java \
        concurrency/src/test/java/yier/bubu/concurrency/cardinality/HyperLogLogTest.java \
        concurrency/src/main/java/yier/bubu/concurrency/cardinality/package-info.java
git commit -m "refactor(redis): move hyperloglog into redis module"
```

### Task 4: Move the Cache-Penetration Example Into Redis Cache Package

**Files:**
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- Create: `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`
- Delete: `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- Delete: `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`

- [ ] **Step 1: Create the moved cache-guard test in the new module/package**

Use the existing test content as the starting point, with:

```java
package yier.bubu.redis.bloomfilter;

import yier.bubu.redis.bloomfilter.BloomFilter;
```

only if the implementation package change makes an explicit import necessary.

- [ ] **Step 2: Run the redis ProductCatalogExistenceGuard test to verify it is red**

Run: `mvn -pl redis -am test -Dtest=ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `ProductCatalogExistenceGuard` is not yet present in the new module/package.

- [ ] **Step 3: Move the ProductCatalogExistenceGuard implementation into the new module/package**

Move the class with the package changed to:

```java
package yier.bubu.redis.bloomfilter;
```

Update the Bloom filter reference to:

```java
import yier.bubu.redis.bloomfilter.BloomFilter;
```

- [ ] **Step 4: Remove the old concurrency cache-guard source and test**

Delete:
- `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`

- [ ] **Step 5: Re-run the redis ProductCatalogExistenceGuard test**

Run: `mvn -pl redis -am test -Dtest=ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS in the new module.

- [ ] **Step 6: Commit the cache example move**

```bash
git add redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java \
        redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java \
        redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java \
        redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java
git commit -m "refactor(redis): move cache guard into redis module"
```

### Task 5: Add Package Docs and Clean Up Concurrency Ownership

**Files:**
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`
- Create: `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`
- Modify or delete: `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java`

- [ ] **Step 1: Add the redis probabilistic package-info**

```java
/**
 * 学习向、单机内存版概率数据结构示例。
 * <p>
 * 这个包当前包含：
 * <ul>
 *   <li>{@link yier.bubu.redis.bloomfilter.BloomFilter}：近似成员查询</li>
 *   <li>{@link yier.bubu.redis.hyperloglog.HyperLogLog}：近似 distinct 计数</li>
 * </ul>
 * <p>
 * 这里的实现刻意保持为“学习向、单节点、纯内存”版本，不涉及 Redis 网络协议、序列化或分布式同步。
 */
package yier.bubu.redis.hyperloglog;
```

- [ ] **Step 2: Add the redis cache package-info**

```java
/**
 * Redis 场景下的缓存防穿透学习示例。
 * <p>
 * 这个包当前包含：
 * <ul>
 *   <li>{@link yier.bubu.redis.bloomfilter.ProductCatalogExistenceGuard}：用布隆过滤器做入口层存在性保护</li>
 * </ul>
 * <p>
 * 示例保持为单机、纯内存、学习向版本。
 */
package yier.bubu.redis.bloomfilter;
```

- [ ] **Step 3: Remove or update obsolete concurrency package-info**

If `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java` would describe an
empty or obsolete package after the move, delete it. If any production type remains there, rewrite
it so it describes only the remaining ownership accurately.

- [ ] **Step 4: Run the redis module tests after package-doc cleanup**

Run: `mvn -pl redis -am test`

Expected: PASS, confirming no package-doc cleanup broke compilation.

- [ ] **Step 5: Commit the package-doc cleanup**

```bash
git add redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java \
        redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java \
        concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java
git commit -m "docs(redis): add package documentation"
```

### Task 6: Update Documentation References to the New Module

**Files:**
- Modify: `redis/docs/hyperloglog.md`
- Modify: `docs/superpowers/specs/2026-03-21-hyperloglog-design.md`
- Modify: `docs/superpowers/plans/2026-03-21-hyperloglog-implementation.md`
- Modify as needed: Bloom-filter-related spec and plan docs that point to old concurrency paths

- [ ] **Step 1: Update HyperLogLog docs to point at redis module paths**

Replace stale implementation references such as:

```text
concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java
```

with:

```text
redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java
```

- [ ] **Step 2: Update BloomFilter and cache-guard design/plan docs to point at redis module paths**

Replace stale references such as:

```text
redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java
redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java
```

with:

```text
redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java
redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java
```

- [ ] **Step 3: Review the documentation diff for stale concurrency ownership**

Run: `rg -n "concurrency/src/main/java/yier/bubu/concurrency/(cache|cardinality)/(BloomFilter|HyperLogLog|ProductCatalogExistenceGuard)" concurrency docs/superpowers`

Expected: no remaining stale references to the old implementation paths.

- [ ] **Step 4: Commit the documentation updates**

```bash
git add redis/docs/hyperloglog.md \
        docs/superpowers/specs/2026-03-21-hyperloglog-design.md \
        docs/superpowers/plans/2026-03-21-hyperloglog-implementation.md \
        docs/superpowers/specs/2026-03-20-bloom-filter-cache-guard-design.md \
        docs/superpowers/plans/2026-03-20-bloom-filter-cache-guard.md
git commit -m "docs(redis): update moved implementation references"
```

### Task 7: Final Verification

**Files:**
- Verify: `pom.xml`
- Verify: `redis/pom.xml`
- Verify: `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- Verify: `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- Verify: `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- Verify: `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- Verify: `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- Verify: `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`

- [ ] **Step 1: Run the focused redis data-structure tests**

Run: `mvn -pl redis -am test -Dtest=BloomFilterTest,HyperLogLogTest,ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, all moved tests green in the new module.

- [ ] **Step 2: Run the full redis module test suite**

Run: `mvn -pl redis -am test`

Expected: PASS, confirming the module is internally consistent.

- [ ] **Step 3: Run the full concurrency module test suite**

Run: `mvn -pl concurrency -am test`

Expected: PASS, confirming the move did not break remaining concurrency examples.

- [ ] **Step 4: Review final scope**

Run: `git diff --stat -- pom.xml redis concurrency docs/superpowers`

Expected: changes are limited to the new redis module, removal of moved code from concurrency, and
reference updates.

- [ ] **Step 5: If a verification step fails, fix the smallest issue and re-run the affected command**

Run: the previously failing Maven or git command

Expected: PASS after the minimal corrective change.

- [ ] **Step 6: Commit only if Step 5 changed tracked files**

```bash
git add pom.xml redis concurrency docs/superpowers
git commit -m "refactor(redis): extract redis teaching module"
```
