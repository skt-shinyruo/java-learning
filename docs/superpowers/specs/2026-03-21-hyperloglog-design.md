# HyperLogLog Design

## Background

This repository is a multi-module Java learning project. The `concurrency` module already contains
small, self-contained examples that explain concurrency-adjacent mechanisms with focused code and
JUnit tests.

The module recently added a `BloomFilter<T>` under `yier.bubu.concurrency.cache`. The repository
also contains a conceptual HyperLogLog document at `redis/docs/hyperloglog.md`.

The new work should add:

- a general-purpose in-memory `HyperLogLog<T>` implementation
- tests that explain add, estimate, and merge behavior
- package and documentation updates so the implementation is discoverable from the existing docs

The implementation should stay dependency-light and match the repository's teaching-oriented style.

## Goals

- Place HyperLogLog in the most natural existing package for this repository
- Provide a reusable generic API centered on `add`, `estimate`, and `merge`
- Keep the implementation JDK-only, readable, and suitable for learning
- Support the core HyperLogLog workflow: insert values, estimate cardinality, merge sketches
- Document the behavior with deterministic tests that tolerate approximation correctly

## Non-Goals

- No Redis-backed or distributed HyperLogLog
- No business wrapper example for this task
- No HyperLogLog++ sparse representation
- No bias-correction lookup tables or advanced empirical tuning
- No deletion support
- No thread-safe mutation guarantees
- No serialization or persistence layer

## Placement

The implementation lives in a dedicated HyperLogLog topic package inside the `redis` module:

- `redis/src/main/java/yier/bubu/redis/hyperloglog`
- `redis/src/test/java/yier/bubu/redis/hyperloglog`

This is preferred over a broader `probabilistic` package because the current structure groups
content by topic: all HyperLogLog-related code and docs stay together under `hyperloglog`.

## Main Type

### `HyperLogLog<T>`

Purpose:

- represent a simple in-memory HyperLogLog sketch
- expose the minimum API needed to teach cardinality estimation and sketch merging

Public API:

- `HyperLogLog(int precision)`
- `HyperLogLog(int precision, ToLongFunction<? super T> hashFunction)`
- `void add(T value)`
- `long estimate()`
- `void merge(HyperLogLog<?> other)`
- `int precision()`
- `int registerCount()`
- `double standardError()`

Design notes:

- `precision` is the only constructor parameter because it is the natural HyperLogLog sizing knob
- an overload accepting `ToLongFunction<? super T>` should allow callers to provide a real 64-bit
  hash source when they already have one
- register count is derived as `m = 1 << precision`
- `estimate()` should compute using `double` internally and return the final value rounded to
  `long`
- `merge(...)` should mutate the receiving sketch in place by taking the per-register maximum
- merging sketches with different precision values should fail fast with `IllegalArgumentException`
- `null` inputs to `add(...)` are not supported and should fail fast with `NullPointerException`
- the class should remain compact and not expose internal register arrays

## Algorithm Strategy

This implementation should be a standard, learning-oriented HyperLogLog rather than a feature-heavy
production variant.

### Hashing

- use a 64-bit hash path so the sketch is not constrained by a narrow 32-bit space
- the overload accepting `ToLongFunction<? super T>` should use the supplied hash values directly
- the default constructor may derive a best-effort 64-bit value from the input object's
  `hashCode()` using a SplitMix64-style bit-mixing function, but this path should be documented as
  the convenience default rather than a substitute for a true 64-bit domain hash
- keep hashing self-contained inside the class with no third-party dependency

### Register Update

- use the leading `precision` bits to select the register index
- use the remaining bits to compute `rho(w)`, the position of the first `1`
- update the target register with the maximum observed `rho`

### Estimation

- use the standard raw estimate based on the harmonic mean of register observations
- include the standard `alpha_m` correction constant based on register count
- when the raw estimate is in the small-range regime and empty registers remain, apply linear
  counting
- do not implement HyperLogLog++ bias tables or sparse encoding for this task

### Merge

- merging should require matching precision and register count
- merge cost should be linear in the number of registers
- merging should preserve the usual HyperLogLog semantics for sketch union

## Error Handling

- reject precision values outside a conservative supported range
- reject `null` values passed to `add(...)`
- reject merges where the other sketch is `null`
- reject merges between sketches with different precision values

The supported precision range should be explicit in the implementation and tests. A reasonable
learning-oriented range is `4 <= precision <= 18`, which keeps memory bounded while still allowing
meaningful accuracy variation.

## Testing Strategy

### `HyperLogLogTest`

The tests should act as executable documentation and cover:

- constructor validation for unsupported precision values
- metadata consistency for `precision()`, `registerCount()`, and `standardError()`
- repeated insertion of the same element does not materially inflate the estimate
- insertion of a known set of distinct values yields an estimate inside a stable tolerance window
- merging two sketches approximates the distinct count of the union
- merging sketches with mismatched precision fails fast

The tests should avoid brittle probabilistic assertions. They should use explicit input sets and
assert the estimate falls within a defined tolerance band rather than expecting an exact count.
For the deterministic cardinality tests in this task:

- use a fixed teaching precision of `p = 10`
- use `registerCount() == 1024` as the matching metadata assertion
- use a fixed dataset size such as `5000` distinct values for estimate and merge scenarios
- use a ±10% error window for the `p = 10` cardinality assertions

## Documentation Updates

### `package-info.java`

Add package-level documentation at
`redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java` to explain:

- the package is for learning-oriented approximate cardinality estimation
- `HyperLogLog` answers approximate distinct-count questions
- the implementation stays local-memory only and does not cover distributed synchronization

### `hyperloglog.md`

Update `redis/docs/hyperloglog.md` to reflect the new repository state:

- remove the claim that the repository does not yet contain a HyperLogLog implementation
- add a short reference to `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- keep the document focused on concepts rather than turning it into API documentation

## Implementation Constraints

- JDK 8 compatible
- no external dependencies
- follow the existing repository style: compact classes, explanatory comments only where useful,
  and tests as executable documentation
- do not introduce unrelated refactoring outside the files needed for HyperLogLog

## Planned Files

- `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`
- `redis/src/main/java/yier/bubu/redis/hyperloglog/package-info.java`
- `redis/src/test/java/yier/bubu/redis/hyperloglog/HyperLogLogTest.java`
- `redis/docs/hyperloglog.md`
