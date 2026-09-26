# Technical Implementation: Async Dependency Evaluation

## Context

The Platform scheduler's dependency guard was experiencing OkHttp thread pool exhaustion when evaluating multiple dataset dependencies. Each dependency check makes blocking I/O calls to MinIO to read `_metadata/metadata.json`, and sequential evaluation caused virtual threads to pile up waiting for the limited OkHttp dispatcher capacity.

## Problem

**Error Pattern**: `java.io.InterruptedIOException: executor rejected`

**Flow**:

1. [`JobScheduler.processClaimedJob()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/JobScheduler.java:186) evaluates dependencies **before** creating outbox messages
2. [`DefaultJobDependencyGuard.checkDependencies()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DefaultJobDependencyGuard.java:58) evaluated each dependency sequentially
3. Each dependency made blocking MinIO calls via [`MinioManifestReader`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/MinioManifestReader.java:38)
4. With jobs checking multiple partitions (e.g., EOD for hundreds of symbols), OkHttp dispatcher queue filled
5. New requests threw `InterruptedIOException: executor rejected`
6. Failed dependency checks prevented jobs from reaching [`prepareDispatch()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducer.java:90) and creating outbox messages

**Impact**: Cascading failure where jobs cannot be dispatched because dependencies cannot be evaluated, blocking the entire job execution flow.

## Solution

Refactored [`DefaultJobDependencyGuard`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DefaultJobDependencyGuard.java) to evaluate all dependencies in parallel using `CompletableFuture` and a virtual thread executor.

### Implementation Details

**Key Changes**:

1. **Virtual Thread Executor** (line 32, 39):

   ```java
   private final ExecutorService ioExecutor;
   this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
   ```

   - Unbounded pool suitable for I/O-bound operations
   - Virtual threads block efficiently without consuming platform threads
   - No thread pool size tuning required

2. **Parallel Dependency Evaluation** (line 73-82):

   ```java
   List<CompletableFuture<DependencyEvaluationResult>> futures = dependencies.stream()
       .map(dep -> CompletableFuture.supplyAsync(
           () -> evaluateDependencyAsync(dep, context),
           ioExecutor
       ))
       .toList();
   ```

   - All dependencies evaluated concurrently
   - Each virtual thread can block on MinIO I/O independently
   - Total wall-clock time reduced from `O(n * latency)` to `O(max_latency)`

3. **Result Aggregation** (line 84-92):
   ```java
   CompletableFuture<Void> allOf = CompletableFuture.allOf(
       futures.toArray(new CompletableFuture[0])
   );
   allOf.join();  // Wait for all to complete
   ```
   - Blocking wait is acceptable since we're already on a virtual thread (scheduler runs on virtual threads)
   - Guarantees all dependency checks complete before proceeding

### Expected Performance Impact

Parallel evaluation can reduce dependency-check wall-clock time when independent
MinIO reads dominate latency. The earlier 10-dependency/200ms example is illustrative,
not measured repository evidence. Current source also configures complementary MinIO
HTTP dispatcher limits, but static inspection does not prove queue exhaustion is gone
under representative load.

## Testing Considerations

1. **Functional Correctness**:

   - All existing dependency evaluation logic unchanged
   - Aggregation logic identical to sequential implementation
   - Error handling preserved per-dependency

2. **Concurrency Safety**:

   - [`ManifestReader`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/ManifestReader.java) implementations must be thread-safe
   - [`CachedManifestReader`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/CachedManifestReader.java) uses Caffeine cache (thread-safe)
   - Evaluators are stateless and safe for concurrent use

3. **Resource Usage**:
   - Virtual threads have minimal memory overhead (~1KB per thread)
   - No platform thread exhaustion risk
   - MinIO client still needs adequate OkHttp dispatcher limits (see Alternative Solutions)

## Alternative Solutions Considered

### Option 1: Increase MinIO OkHttp Limits (Complementary)

```java
OkHttpClient httpClient = new OkHttpClient.Builder()
    .dispatcher(new Dispatcher() {{
        setMaxRequests(256);
        setMaxRequestsPerHost(128);
    }})
    .build();
```

- **Pro**: Simple configuration change
- **Con**: Doesn't reduce total blocking time, just increases capacity
- **Recommendation**: Apply this **in addition** to async evaluation for defense-in-depth

### Option 2: Cached Metadata Service (Future Enhancement)

- Background refresh service populates in-memory metadata cache
- Dependency checks read from cache (no I/O)
- **Pro**: Zero-latency dependency checks
- **Con**: Requires cache invalidation strategy, eventual consistency
- **Recommendation**: Consider for Phase 5+ if async evaluation proves insufficient

## Related Work

- [ADR-007: Scheduler Claim and Outbox Boundary](../adr/007-scheduler-claim-and-outbox-boundary.md)
- [Job Execution Flow](../flows/001-job-execution.md)
- [Phase 4: Job Dependency Guard](../../plans/roadmap/phase-4-job-dependency-guard.md)

## Current Source Assessment

- **Source present:** dependency checks use `CompletableFuture` with a virtual-thread
  executor.
- **Source present:** the MinIO client configures complementary HTTP dispatcher limits.
- **Current risk:** the executor is effectively unbounded and its lifecycle/closure is
  not explicit in the guard.
- **Evidence present only as source coverage:** unit tests exercise dependency outcomes,
  but static inspection does not establish representative parallel/load behavior.
- **Evidence-dependent:** latency improvement, queue-pressure removal, resource bounds,
  and production stability remain unverified.

## Recommended Actions

1. Replace illustrative performance claims with a controlled multi-dependency benchmark
   before treating the implementation as a capacity result.
2. Add concurrency tests for mixed success, blocked, timeout, and exception outcomes
   while preserving deterministic aggregate semantics.
3. Define ownership and shutdown of the virtual-thread executor, or inject a managed
   executor with an explicit lifecycle.
4. Bound or gate concurrent MinIO requests based on measured dispatcher/storage capacity;
   virtual threads do not remove downstream connection limits.
5. Capture queue wait, request latency, dependency count, and scheduler dispatch latency
   without high-cardinality metrics.
6. Keep cached metadata as a separate future decision; do not add a second readiness
   authority unless measured I/O remains a bottleneck.

## Status

**Implementation source present:** 2026-09-15
**Verification:** integration/load evidence for representative multi-dependency jobs is
not recorded; executable checks were not run for this documentation update.
