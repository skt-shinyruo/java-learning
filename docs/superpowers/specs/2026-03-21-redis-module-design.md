# Redis Module Design

## Background

This repository is a multi-module Java learning project. The root build currently contains:

- `base`
- `jdk`
- `jvm`
- `netty`
- `concurrency`

The `concurrency` module currently contains three Redis-adjacent learning artifacts that no longer
fit the module or package boundaries cleanly:

- `BloomFilter<T>` as a membership-oriented probabilistic structure
- `HyperLogLog<T>` as a cardinality-estimation structure
- `ProductCatalogExistenceGuard` as a cache-penetration example that wraps Bloom filter semantics in
  business language

These pieces have already been implemented as learning-oriented, in-memory examples, but they do
not logically belong under `concurrency`.

## Goals

- Add a dedicated `redis` Maven module to the project
- Move Redis-adjacent learning code out of `concurrency`
- Keep `BloomFilter<T>` and `HyperLogLog<T>` together in the same module
- Move the cache-penetration example into the same `redis` module
- Keep package boundaries inside the new module clear and focused
- Preserve the existing APIs and behavior of the moved classes
- Update tests and docs so the new locations are discoverable and verifiable

## Non-Goals

- No actual Redis client integration
- No networked Redis demos
- No serialization, persistence, or protocol implementation
- No redesign of `BloomFilter<T>` or `HyperLogLog<T>` behavior during this move
- No broad refactor of unrelated `concurrency` examples

## Module Placement

Add a new top-level Maven module:

- `redis`

The root `pom.xml` should include `<module>redis</module>`.

The new module should follow the same basic conventions as the existing modules:

- parent is `java-learning`
- dependency on `base`
- `junit` for tests
- JDK 8 compatible

## Package Structure Inside `redis`

The new module should contain two topic-focused packages.

### `yier.bubu.redis.hyperloglog`

Purpose:

- hold HyperLogLog-related learning content

Initial contents:

- `HyperLogLog<T>`
- `HyperLogLogTest`
- HyperLogLog-focused package documentation

This groups approximate cardinality estimation content by topic rather than by a broader algorithm
family.

### `yier.bubu.redis.bloomfilter`

Purpose:

- hold BloomFilter-related learning content

Initial contents:

- `BloomFilter<T>`
- `ProductCatalogExistenceGuard`
- BloomFilter-focused package documentation

This groups the generic Bloom filter and its cache-penetration example together because the example
is explicitly part of the BloomFilter topic.

## Source and Test Moves

Move the following production classes:

- `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- `concurrency/src/main/java/yier/bubu/concurrency/cardinality/HyperLogLog.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`

To:

- `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`

Move the following tests:

- `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- `concurrency/src/test/java/yier/bubu/concurrency/cardinality/HyperLogLogTest.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`

To:

- `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`

## Package Documentation

Add:

- `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`

The HyperLogLog package docs should explain:

- this is a learning-oriented, in-memory HyperLogLog example
- `HyperLogLog` answers approximate distinct-count questions
- this module is not a real Redis server/client implementation

The BloomFilter package docs should explain:

- `BloomFilter` answers membership-style questions
- `ProductCatalogExistenceGuard` demonstrates BloomFilter-based cache-penetration protection
- the implementation remains single-node and learning-oriented

## Concurrency Module Cleanup

After moving the code:

- remove the moved classes from `concurrency`
- remove or update any `package-info.java` files that would otherwise describe code that no longer
  exists there
- leave unrelated `concurrency` examples unchanged

If `concurrency/src/main/java/yier/bubu/concurrency/cache/package-info.java` becomes obsolete after
the move, it should be removed rather than left describing a package with no remaining production
types.

## Documentation Updates

Update all repository documentation that references the old `concurrency` locations, including:

- `redis/docs/hyperloglog.md`
- `docs/superpowers/specs/2026-03-21-hyperloglog-design.md`
- `docs/superpowers/plans/2026-03-21-hyperloglog-implementation.md`
- any Bloom-filter-related design or plan docs that point at old paths

These docs should reference the new `redis` module paths so the repository does not contain stale
implementation references.

## Testing Strategy

Verification should be done at two levels.

### Redis Module Focused Verification

Run:

- `mvn -pl redis -am test -Dtest=BloomFilterTest,HyperLogLogTest,ProductCatalogExistenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

This confirms the moved code and tests still pass in the new module.

### Module-Level Regression Verification

Run:

- `mvn -pl redis -am test`
- `mvn -pl concurrency -am test`

This confirms:

- the new module builds and tests correctly
- `concurrency` still builds and its remaining examples are unaffected

## Implementation Constraints

- Keep the moved classes behaviorally unchanged unless a move-related import/package fix requires a
  small adjustment
- Use the smallest viable structural change to achieve the new module boundary
- Do not introduce Redis client libraries or extra dependencies
- Keep tests as executable documentation
- Do not leave duplicated copies of the same class across `concurrency` and `redis`

## Planned Files

- `pom.xml`
- `redis/pom.xml`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`
- `redis/docs/hyperloglog.md`
- `docs/superpowers/specs/2026-03-21-hyperloglog-design.md`
- `docs/superpowers/plans/2026-03-21-hyperloglog-implementation.md`
