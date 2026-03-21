# HyperLogLog Daily Active User Counter Design

## Background

The `redis` module already contains a learning-oriented `HyperLogLog<T>` implementation under
`yier.bubu.redis.hyperloglog`. That class explains the data structure itself, but it does not yet
show a small business-facing example analogous to the existing Bloom-filter practice
`ProductCatalogExistenceGuard`.

For HyperLogLog, the most natural teaching scenario is UV counting:

- record user visits
- estimate how many unique users visited in a day
- optionally merge partial counters from multiple shards

## Goals

- Add a thin business wrapper around `HyperLogLog<Long>` for daily UV counting
- Keep the API small and business-facing
- Use tests as executable documentation for repeated visits, UV estimation, and shard merge
- Keep the example in the `hyperloglog` topic package rather than creating another package

## Non-Goals

- No multi-day tracker or date-indexed manager
- No persistence or Redis command integration
- No exact distinct counting fallback
- No thread-safety guarantees beyond the underlying learning-oriented implementation
- No changes to the `HyperLogLog<T>` algorithm itself unless needed for integration correctness

## Placement

The new practice should live alongside `HyperLogLog<T>` in the same topic package:

- `redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java`
- `redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java`

This is preferred over a separate `uv` or `analytics` package because the class is specifically a
HyperLogLog example, not a broader analytics subsystem.

## Main Type

### `DailyActiveUserCounter`

Purpose:

- wrap `HyperLogLog<Long>` in daily-active-user business language
- demonstrate how HLL is used for approximate UV counting

Public API:

- `DailyActiveUserCounter(int precision)`
- `void recordVisit(long userId)`
- `long estimateDailyActiveUsers()`
- `void merge(DailyActiveUserCounter other)`

Design notes:

- internally hold one `HyperLogLog<Long>`
- `recordVisit(...)` should forward the user ID into the underlying sketch
- `estimateDailyActiveUsers()` should return the current approximate UV
- `merge(...)` should merge another daily counter into the current one
- merging counters with different precision values should fail fast via the underlying HLL behavior

## Testing Strategy

### `DailyActiveUserCounterTest`

The tests should use business language and cover:

- a fixed user set produces an estimate within a stable tolerance window
- repeated visits from the same user do not materially inflate UV
- merging two shard-level counters approximates the union UV
- merging counters with different precision fails fast

For deterministic cardinality tests:

- use a fixed precision such as `10`
- use a fixed input size such as `5000` distinct user IDs
- use a ±10% error window, matching the existing HyperLogLog learning tests

## Documentation Updates

Update `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java` to mention:

- `HyperLogLog` as the generic structure
- `DailyActiveUserCounter` as the business-facing UV example built on top of it

No large document rewrite is needed. The example should be discoverable from package docs and tests.

## Implementation Constraints

- Keep the wrapper thin and readable
- Do not add date management or multiple counters in one class
- Follow the repository's JDK-only, test-driven style
- Keep names and assertions in business terms where possible

## Planned Files

- `redis/src/main/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounter.java`
- `redis/src/test/java/yier/bubu/redis/hyperloglog/DailyActiveUserCounterTest.java`
- `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
