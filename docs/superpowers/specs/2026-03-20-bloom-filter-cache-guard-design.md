# Bloom Filter Cache Guard Design

## Background

This repository is a multi-module Java learning project. The `concurrency` module already contains
small, self-contained algorithm and mechanism examples with "tests as executable documentation".

The new work should add:

- a general-purpose in-memory Bloom filter implementation
- a business-oriented example that shows how Bloom filters help reduce cache penetration

The implementation should stay dependency-light and fit the existing teaching-oriented style.

## Goals

- Place the Bloom filter code in a module and package that match its teaching purpose
- Provide a reusable `BloomFilter<T>` implementation using only the JDK
- Provide a business example that maps the data structure to cache-penetration protection
- Keep the API small and readable
- Use tests to explain both the data structure behavior and the business scenario

## Non-Goals

- No distributed Bloom filter
- No Redis integration
- No counting Bloom filter or deletion support
- No highly optimized hashing framework or third-party dependency
- No production-grade persistence or serialization

## Placement

The implementation lives in a dedicated BloomFilter topic package inside the `redis` module:

- `redis/src/main/java/yier/bubu/redis/bloomfilter`
- `redis/src/test/java/yier/bubu/redis/bloomfilter`

This groups the generic Bloom filter and the cache-penetration example together under the same
topic, instead of splitting them by abstraction layer.

## Main Types

### `BloomFilter<T>`

Purpose:

- represent a simple, in-memory Bloom filter
- expose the minimum API needed to teach insert/query semantics and parameter sizing

Public API:

- `BloomFilter(long expectedInsertions, double falsePositiveProbability)`
- `void put(T value)`
- `boolean mightContain(T value)`
- `long expectedInsertions()`
- `double falsePositiveProbability()`
- `int bitSize()`
- `int hashFunctionCount()`

Design notes:

- store bits in a `BitSet`
- derive bit-array size and number of hash functions from `expectedInsertions` and
  `falsePositiveProbability`
- use double hashing to generate `k` logical hash locations from two base hashes
- reject invalid constructor arguments with `IllegalArgumentException`
- keep the implementation single-process and easy to read

### `ProductCatalogExistenceGuard`

Purpose:

- wrap the generic Bloom filter in business language
- demonstrate how to reduce unnecessary backend lookups for obviously invalid product IDs

Public API:

- `ProductCatalogExistenceGuard(Collection<Long> existingProductIds, double falsePositiveProbability)`
- `boolean shouldQueryBackend(long productId)`
- `void addProduct(long productId)`

Design notes:

- initialize the internal Bloom filter from known existing product IDs
- return `false` when the Bloom filter says the product definitely does not exist
- return `true` when the Bloom filter says the product might exist, meaning Redis or DB can still be queried
- keep the wrapper thin so that the example stays focused on the Bloom filter's role at the cache boundary

## Package Documentation

Add `package-info.java` for `yier.bubu.redis.bloomfilter` to explain:

- the package groups BloomFilter-related learning content
- `BloomFilter` answers "definitely not present" or "might be present"
- `ProductCatalogExistenceGuard` is a BloomFilter-based cache-penetration example
- the implementation is intentionally local-memory only and for learning purposes

## Testing Strategy

### `BloomFilterTest`

The tests should act as executable documentation and cover:

- inserted elements are reported as `mightContain == true`
- the filter does not produce false negatives for inserted elements
- sizing parameters create positive bit-array and hash-function counts
- invalid constructor arguments are rejected
- more generous bit budgets imply lower theoretical false-positive probability

The tests should avoid fragile randomness. They should verify stable, deterministic behavior and
small formula-derived expectations.

### `ProductCatalogExistenceGuardTest`

The tests should show the cache-penetration scenario in business language:

- known existing product IDs are allowed to continue to the backend
- clearly non-existing product IDs can be blocked before Redis/DB lookup
- the guard reduces invalid backend queries but cannot promise perfect rejection because Bloom filters
  allow false positives

The tests should use small, explicit ID sets so the scenario remains easy to follow.

## Error Handling

- constructor arguments must be validated early
- `null` values are not supported in the generic Bloom filter
- invalid expected insertion count or invalid false-positive probability should fail fast

## Implementation Constraints

- JDK 8 compatible
- no external dependencies
- follow the existing repository style: compact classes, explanatory comments only where useful,
  and documentation-rich tests

## Planned Files

- `redis/src/main/java/yier/bubu/redis/bloomfilter/BloomFilter.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuard.java`
- `redis/src/main/java/yier/bubu/redis/bloomfilter/package-info.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/BloomFilterTest.java`
- `redis/src/test/java/yier/bubu/redis/bloomfilter/ProductCatalogExistenceGuardTest.java`
