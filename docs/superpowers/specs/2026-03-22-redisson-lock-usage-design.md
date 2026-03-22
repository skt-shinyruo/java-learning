# Redisson Lock Usage Design

## Background

This repository is a multi-module Java learning project. The `redis` module already contains
teaching-oriented examples that use tests as executable documentation so readers can run small,
focused scenarios and inspect the behavior directly.

The repository now needs concrete Redisson usage examples for two current lock choices:

- `RLock` for ordinary distributed mutual exclusion
- `RFencedLock` for mutual exclusion plus fencing-token based stale-owner protection

The user has confirmed that the examples should be real integration examples against an already
running local Redis server on `127.0.0.1:6379` with no password. The tests must connect to that
server directly and must not attempt to start Redis themselves.

## Goals

- Add runnable Redisson integration examples under the `redis` module
- Keep the runnable entry points in test code so beginners can execute them directly
- Show the minimum `RLock` workflow needed to understand basic distributed locking
- Show the core `RFencedLock` workflow, including why fencing tokens exist
- Keep the examples small, deterministic, and readable
- Update package documentation so the lock package accurately describes current recommendations

## Non-Goals

- No embedded Redis server
- No Docker or Testcontainers based setup
- No attempt to teach every Redisson synchronizer type
- No broad redesign of the existing Redis learning module
- No production-grade resilience framework around Redis connectivity

## Placement

The new examples should live in the existing Redisson topic package inside the `redis` module.

Main-source documentation:

- `redis/src/main/java/yier/bubu/redis/redisson/package-info.java`

Test-source executable examples:

- `redis/src/test/java/yier/bubu/redis/redisson/LocalRedissonTestSupport.java`
- `redis/src/test/java/yier/bubu/redis/redisson/RLockIntegrationTest.java`
- `redis/src/test/java/yier/bubu/redis/redisson/RFencedLockIntegrationTest.java`

The main source tree should stay documentation-focused here. The actual runnable examples should sit
in tests, matching the repository's existing teaching style.

## Dependency Changes

`redis/pom.xml` should add a Redisson dependency so the test code can create `RedissonClient`,
`RLock`, and `RFencedLock` instances.

The dependency should:

- be scoped so the examples compile and run in this module
- remain compatible with the repository's current Java 8 baseline

JUnit 4 remains the test framework.

## Package Documentation

The package documentation for `yier.bubu.redis.redisson` should explain:

- the package contains learning-oriented Redisson lock examples
- `RLock` is the default distributed lock example for ordinary mutual exclusion
- `RFencedLock` is the recommended example when stale-owner protection matters
- `RedLock` is deprecated in current Redisson guidance and should be described, if mentioned at all,
  only as historical context rather than as the recommended starting point

The wording should be explicit that current production-oriented learning should start from `RLock`
and `RFencedLock`, not from `RedLock`.

## Test Support Design

### `LocalRedissonTestSupport`

Purpose:

- centralize connection configuration for the local Redis instance
- reduce duplication across the two integration test classes
- provide simple key cleanup helpers so tests do not interfere with each other

Responsibilities:

- create one or more `RedissonClient` instances pointed at `redis://127.0.0.1:6379`
- close clients reliably in test teardown
- expose helper methods for deleting the specific keys used by these examples before each test

Constraints:

- do not flush the whole Redis database
- only delete the keys owned by these tests
- keep the helper small and test-only

## `RLock` Example Design

### Test class

- `RLockIntegrationTest`

### Purpose

Teach the minimum practical `RLock` workflow:

- obtain a named lock
- enter a critical section from one client
- observe that a second client cannot acquire the same lock at the same time
- release the lock and allow the second client to acquire it

### Planned tests

#### `rLock_shouldAllowOnlyOneClientToEnterCriticalSection`

Use two separate `RedissonClient` instances and the same lock name.

Expected behavior:

- client A acquires the lock
- client B calls `tryLock` with no wait window and does not acquire the same lock
- the assertions make the exclusivity visible without relying on fragile timing races

#### `rLock_shouldBecomeAvailableAfterUnlock`

Expected behavior:

- client A acquires and then releases the lock
- client B can subsequently acquire the same lock
- the test demonstrates the complete basic lifecycle: lock, fail to re-enter from another client,
  unlock, retry successfully

### Design notes

- favor short, mostly sequential tests over multi-threaded choreography
- keep lock names stable and namespaced, for example `learning:redisson:rlock:*`
- use explicit cleanup for the keys touched by the tests

## `RFencedLock` Example Design

### Test class

- `RFencedLockIntegrationTest`

### Purpose

Teach what makes `RFencedLock` different from `RLock`:

- successful lock acquisition returns a fencing token
- fencing tokens increase over time for the same protected resource
- a downstream resource can reject stale writers by comparing tokens

### Protected resource model

The test class should contain a very small in-memory helper representing a protected downstream
resource. The helper should store:

- the largest accepted fencing token
- the last accepted value or writer marker

It should expose one operation similar to:

- `boolean tryWrite(long token, String value)`

Behavior:

- accept the write only when `token` is greater than the currently accepted token
- reject writes with stale tokens

This helper is intentionally local and in-memory. Its job is to demonstrate the semantics that a
real downstream system would enforce, not to simulate a real database.

### Planned tests

#### `fencedLock_shouldReturnMonotonicallyIncreasingTokens`

Expected behavior:

- first successful acquisition returns token 1-style value
- a later successful acquisition for the same resource returns a larger token
- the test proves that `RFencedLock` gives more than plain mutual exclusion

#### `fencedLock_shouldLetProtectedResourceRejectStaleOwner`

Scenario:

1. client A acquires the fenced lock and gets `token1`
2. client A releases the lock
3. client B acquires the same fenced lock and gets `token2`
4. the protected resource accepts a write performed with `token2`
5. a later write attempt with stale `token1` is rejected

Expected outcome:

- the resource state reflects only the write guarded by the newest token
- the test makes the stale-owner protection visible in a way beginners can follow

### Design notes

- the example should not try to manufacture GC pauses or network partitions
- the stale-owner problem should be explained through token ordering, not through unreliable timing
- keep key names stable and namespaced, for example `learning:redisson:fenced:*`

## Runtime Assumptions

The tests assume:

- Redis is already running locally on `127.0.0.1:6379`
- no password is required
- the current user is comfortable running integration tests against that local instance

If Redis is unavailable, the tests will fail fast during client operations. That is acceptable for
this learning-oriented integration setup, but the test comments should make the prerequisite obvious.

## Testing Strategy

Follow TDD for the implementation work:

1. add the new test classes first
2. run the targeted tests and confirm failure for the expected reasons
3. add the Redisson dependency and test support code
4. rerun until the targeted tests pass
5. run the focused Redisson lock test set together

Primary verification command:

- `mvn -pl redis -am test -Dtest=RLockIntegrationTest,RFencedLockIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Optional broader verification after the focused tests are green:

- `mvn -pl redis -am test`

## Implementation Constraints

- Java 8 compatible
- follow existing repository style: compact examples, readable assertions, minimal indirection
- keep helper code test-only unless documentation requires otherwise
- do not start or stop Redis from the test suite
- do not delete unrelated Redis keys from the local instance

## Planned Files

- Modify: `redis/pom.xml`
- Create: `redis/src/main/java/yier/bubu/redis/redisson/package-info.java`
- Create: `redis/src/test/java/yier/bubu/redis/redisson/LocalRedissonTestSupport.java`
- Create: `redis/src/test/java/yier/bubu/redis/redisson/RLockIntegrationTest.java`
- Create: `redis/src/test/java/yier/bubu/redis/redisson/RFencedLockIntegrationTest.java`
