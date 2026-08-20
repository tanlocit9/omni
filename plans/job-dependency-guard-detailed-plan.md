# Job Dependency Guard - Detailed Implementation Plan

## Executive Summary

Transform job dependency metadata from documentation-only to runtime enforcement using dataset manifests as the source of truth. This enables:

- **Hard data dependencies**: Jobs wait for READY datasets, not just cron timing
- **Cross-machine coordination**: Multiple scheduler instances share manifest state via S3/R2
- **Lineage-aware execution**: Detect and prevent execution against stale upstream data
- **No false failures**: BLOCKED jobs defer without creating failure history noise

## Reference Documents

- [Job Dependency Guard Implementation Plan](../docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md) - High-level goals and outcomes
- [Dataset Metadata Manifest](../docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md) - Prerequisite manifest infrastructure
- [Dataset Manifest Rule](../AGENTS.md#dataset-manifest-rule) - Repository guidance
- [Data Lake Documentation](../docs/data/data-lake.md) - Manifest schema and semantics

## Architecture Overview

### Current Scheduler Flow

```
JobScheduler.scan() (every 30s)
  → SchedulerClaimService.claimDueJobs(now)
     → SQL: SELECT ... WHERE nextRun <= now AND isActive = true
     → UPDATE claim_token, claimed_by, claim_until
  → JobDefinitionRepository.findById(claimId)
  → JobProducerRegistry.getProducer(jobType)
     → producer.prepareDispatch(job, claim, now)
        → JobService.prepareClaimedExecution(job, claim, now)
           → create JobExecutionHistory (PENDING)
           → update job.nextRun
           → enqueue outbox messages
```

### New Flow with Dependency Guard

```
JobScheduler.scan() (every 30s)
  → SchedulerClaimService.claimDueJobs(now)
  → for each claim:
     → JobDependencyGuard.check(job, context)  ← NEW
        → parse dependsOnDatasets from config
        → for each DatasetDependency:
           → ManifestReader.readManifest(dataset, partition)
           → evaluate conditions (EXISTS, READY, CURRENT_INPUTS...)
        → return DependencyCheckResult

     → if result.isBlocked():
        → BlockedJobTracker.recordBlocked(job, result.reason, now)  ← NEW
        → SchedulerClaimService.releaseClaim(claim)
        → log warning, continue to next claim

     → if result.isReady():
        → JobProducerRegistry.getProducer(jobType)
           → producer.prepareDispatch(job, claim, now)
```

### Key Design Decisions

1. **Guard placement**: Before claim → execution transition, after claim acquisition
2. **Blocked state tracking**: In-memory or lightweight DB table, NOT job_execution_history
3. **Retry strategy**: Bounded exponential backoff (30s → 1m → 2m → max 5m)
4. **Manifest caching**: Short TTL cache (30-60s) to reduce S3 reads
5. **Dependency mode**: Config flag per job (`DOCUMENTATION_ONLY` vs `ENFORCED`)
6. **Incremental migration**: Existing jobs stay `DOCUMENTATION_ONLY`, opt-in to enforcement

## Component Breakdown

### Phase 1: Domain Models (COMPLETED)

#### 1.1 DatasetRef

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DatasetRef.java`

```java
public final class DatasetRef {
    private final String dataset;           // "eod", "indicators", "signals"
    private final Map<String, String> partition;  // {"exchange": "HOSE", "date": "2026-08-11"}

    public static DatasetRef of(String dataset, Map<String, String> partition);
    public static DatasetRef of(String dataset);  // no partition
}
```

**Status**: ✅ Created

#### 1.2 DependencyCondition (Enum)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyCondition.java`

```java
public enum DependencyCondition {
    EXISTS,                    // manifest file exists
    READY,                     // manifest.status == "READY"
    PARTITION_MATCH,           // manifest.partition matches expected
    MIN_ROW_COUNT,             // manifest.rowCount >= threshold
    SUPPORTED_SCHEMA_VERSION,  // manifest.schemaVersion in range
    MAX_FRESHNESS_LAG,         // now - manifest.generatedAt <= maxLag
    CURRENT_INPUTS             // manifest.inputs[].dataVersion matches current upstream
}
```

**Status**: ✅ Created

#### 1.3 DependencyStatus (Enum)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyStatus.java`

```java
public enum DependencyStatus {
    READY,                    // all conditions satisfied
    MISSING,                  // manifest does not exist
    NOT_READY,                // status != READY
    STALE,                    // data is old relative to upstream
    EMPTY,                    // rowCount < threshold
    INVALID_SCHEMA,           // schemaVersion not supported
    INPUT_VERSION_MISMATCH,   // lineage dataVersion mismatch
    ERROR                     // I/O failure
}
```

**Status**: ✅ Created

#### 1.4 DependencyCheckResult

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyCheckResult.java`

```java
public final class DependencyCheckResult {
    private final DependencyStatus status;
    private final String reason;
    private final DatasetRef datasetRef;

    public static DependencyCheckResult ready();
    public static DependencyCheckResult missing(DatasetRef ref);
    public static DependencyCheckResult notReady(DatasetRef ref, String manifestStatus);
    public static DependencyCheckResult stale(DatasetRef ref, String detail);
    // ... other factory methods

    public boolean isReady();
    public boolean isBlocked();
}
```

**Status**: ✅ Created

### Phase 2: Dataset Manifest Java Model

#### 2.1 DatasetManifest (Record)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/models/DatasetManifest.java`

```java
public record DatasetManifest(
    int version,
    String dataset,
    Map<String, String> partition,
    String status,                    // "READY", "PROCESSING", "FAILED"
    String dataVersion,               // "sha256:..."
    String path,
    long rowCount,
    int columnCount,
    List<ColumnMetadata> columns,
    int schemaVersion,
    String schemaHash,
    String minTimestamp,              // nullable
    String maxTimestamp,              // nullable
    List<DatasetInput> inputs,        // nullable - lineage
    String sourceExecutionId,         // nullable
    String generatedAt                // ISO 8601 UTC
) {
    public boolean isReady() {
        return "READY".equals(status);
    }

    public boolean isProcessing() {
        return "PROCESSING".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
```

#### 2.2 ColumnMetadata (Record)

**File**: Same as above

```java
public record ColumnMetadata(
    String name,
    String type,        // "BIGINT", "DOUBLE", "VARCHAR", "TIMESTAMP", "BOOLEAN"
    boolean nullable
) {}
```

#### 2.3 DatasetInput (Record)

**File**: Same as above

```java
public record DatasetInput(
    String dataset,
    Map<String, String> partition,
    String dataVersion
) {}
```

### Phase 3: Manifest Reader Infrastructure

#### 3.1 ManifestReader (Interface)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/ManifestReader.java`

```java
public interface ManifestReader {
    /**
     * Read dataset manifest from object storage.
     *
     * @return Optional.empty() if manifest does not exist
     * @throws ManifestReadException if I/O error occurs
     */
    Optional<DatasetManifest> readManifest(DatasetRef ref);

    /**
     * Check if manifest exists without reading full content.
     */
    boolean manifestExists(DatasetRef ref);
}
```

#### 3.2 MinioManifestReader (Implementation)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/MinioManifestReader.java`

```java
@Service
@RequiredArgsConstructor
public class MinioManifestReader implements ManifestReader {
    private final MinioClient minioClient;
    private final String bucket;  // from app.minio.bucket

    @Override
    public Optional<DatasetManifest> readManifest(DatasetRef ref) {
        String objectPath = buildManifestPath(ref);

        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build())) {

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(parseManifest(json));

        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();  // manifest does not exist
            }
            throw new ManifestReadException("Failed to read manifest: " + ref, e);
        } catch (Exception e) {
            throw new ManifestReadException("Failed to read manifest: " + ref, e);
        }
    }

    @Override
    public boolean manifestExists(DatasetRef ref) {
        String objectPath = buildManifestPath(ref);
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new ManifestReadException("Failed to check manifest existence: " + ref, e);
        } catch (Exception e) {
            throw new ManifestReadException("Failed to check manifest existence: " + ref, e);
        }
    }

    private String buildManifestPath(DatasetRef ref) {
        // _metadata/datasets/{dataset}/{partition_path}.json
        String partitionPath = buildPartitionPath(ref.getPartition());
        return String.format("_metadata/datasets/%s/%s.json",
            ref.getDataset(), partitionPath);
    }

    private String buildPartitionPath(Map<String, String> partition) {
        if (partition.isEmpty()) {
            return "default";  // or throw for partition-required datasets
        }
        // Sort keys for deterministic path: exchange=HOSE/date=2026-08-11
        return partition.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("/"));
    }

    private DatasetManifest parseManifest(String json) {
        // Use Jackson ObjectMapper
        try {
            return objectMapper.readValue(json, DatasetManifest.class);
        } catch (JsonProcessingException e) {
            throw new ManifestReadException("Failed to parse manifest JSON", e);
        }
    }
}
```

#### 3.3 CachedManifestReader (Decorator)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/CachedManifestReader.java`

```java
@Service
@RequiredArgsConstructor
public class CachedManifestReader implements ManifestReader {
    private final ManifestReader delegate;
    private final Cache<DatasetRef, Optional<DatasetManifest>> cache;

    // Caffeine cache: 60s TTL, max 1000 entries

    @Override
    public Optional<DatasetManifest> readManifest(DatasetRef ref) {
        return cache.get(ref, delegate::readManifest);
    }

    @Override
    public boolean manifestExists(DatasetRef ref) {
        // Don't cache existence checks - too transient
        return delegate.manifestExists(ref);
    }
}
```

#### 3.4 ManifestReadException

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/ManifestReadException.java`

```java
public class ManifestReadException extends RuntimeException {
    public ManifestReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### Phase 4: Dependency Condition Evaluators

#### 4.1 DependencyConditionEvaluator (Interface)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/DependencyConditionEvaluator.java`

```java
public interface DependencyConditionEvaluator {
    DependencyCondition getCondition();

    DependencyCheckResult evaluate(
        DatasetRef ref,
        Optional<DatasetManifest> manifest,
        Map<String, Object> parameters);
}
```

#### 4.2 ExistsEvaluator

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/ExistsEvaluator.java`

```java
@Component
public class ExistsEvaluator implements DependencyConditionEvaluator {
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.EXISTS;
    }

    @Override
    public DependencyCheckResult evaluate(
            DatasetRef ref,
            Optional<DatasetManifest> manifest,
            Map<String, Object> parameters) {

        if (manifest.isEmpty()) {
            return DependencyCheckResult.missing(ref);
        }
        return DependencyCheckResult.ready();
    }
}
```

#### 4.3 ReadyEvaluator

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/ReadyEvaluator.java`

```java
@Component
public class ReadyEvaluator implements DependencyConditionEvaluator {
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.READY;
    }

    @Override
    public DependencyCheckResult evaluate(
            DatasetRef ref,
            Optional<DatasetManifest> manifest,
            Map<String, Object> parameters) {

        if (manifest.isEmpty()) {
            return DependencyCheckResult.missing(ref);
        }

        DatasetManifest m = manifest.get();
        if (!m.isReady()) {
            return DependencyCheckResult.notReady(ref, m.status());
        }

        return DependencyCheckResult.ready();
    }
}
```

#### 4.4 MinRowCountEvaluator

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/MinRowCountEvaluator.java`

```java
@Component
public class MinRowCountEvaluator implements DependencyConditionEvaluator {
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.MIN_ROW_COUNT;
    }

    @Override
    public DependencyCheckResult evaluate(
            DatasetRef ref,
            Optional<DatasetManifest> manifest,
            Map<String, Object> parameters) {

        if (manifest.isEmpty()) {
            return DependencyCheckResult.missing(ref);
        }

        long minRows = ((Number) parameters.getOrDefault("minRows", 1L)).longValue();
        DatasetManifest m = manifest.get();

        if (m.rowCount() < minRows) {
            return DependencyCheckResult.empty(ref, m.rowCount(), minRows);
        }

        return DependencyCheckResult.ready();
    }
}
```

#### 4.5 CurrentInputsEvaluator

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/CurrentInputsEvaluator.java`

```java
@Component
@RequiredArgsConstructor
public class CurrentInputsEvaluator implements DependencyConditionEvaluator {
    private final ManifestReader manifestReader;

    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.CURRENT_INPUTS;
    }

    @Override
    public DependencyCheckResult evaluate(
            DatasetRef ref,
            Optional<DatasetManifest> manifest,
            Map<String, Object> parameters) {

        if (manifest.isEmpty()) {
            return DependencyCheckResult.missing(ref);
        }

        DatasetManifest downstream = manifest.get();
        if (downstream.inputs() == null || downstream.inputs().isEmpty()) {
            // No lineage recorded - assume OK
            return DependencyCheckResult.ready();
        }

        // Check each upstream input's current dataVersion
        for (DatasetInput input : downstream.inputs()) {
            DatasetRef upstreamRef = DatasetRef.of(input.dataset(), input.partition());
            Optional<DatasetManifest> upstreamManifest = manifestReader.readManifest(upstreamRef);

            if (upstreamManifest.isEmpty()) {
                // Upstream disappeared - block
                return DependencyCheckResult.missing(upstreamRef);
            }

            String currentVersion = upstreamManifest.get().dataVersion();
            String recordedVersion = input.dataVersion();

            if (!currentVersion.equals(recordedVersion)) {
                return DependencyCheckResult.inputVersionMismatch(
                    ref, input.dataset(), currentVersion, recordedVersion);
            }
        }

        return DependencyCheckResult.ready();
    }
}
```

### Phase 5: Job Dependency Guard

#### 5.1 DatasetDependency (Config Model)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DatasetDependency.java`

```java
public record DatasetDependency(
    DatasetRef datasetRef,
    List<DependencyCondition> conditions,
    Map<String, Object> parameters
) {
    public static DatasetDependency of(String dataset, Map<String, String> partition) {
        return new DatasetDependency(
            DatasetRef.of(dataset, partition),
            List.of(DependencyCondition.EXISTS, DependencyCondition.READY),
            Map.of()
        );
    }

    public static DatasetDependency withConditions(
            String dataset,
            Map<String, String> partition,
            DependencyCondition... conditions) {
        return new DatasetDependency(
            DatasetRef.of(dataset, partition),
            List.of(conditions),
            Map.of()
        );
    }
}
```

#### 5.2 JobDependencyGuard (Interface)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/JobDependencyGuard.java`

```java
public interface JobDependencyGuard {
    /**
     * Check if job dependencies are satisfied.
     *
     * @param job job definition with dependency config
     * @param context execution context (timestamp, partition hints)
     * @return READY if all dependencies satisfied, or blocked result with reason
     */
    DependencyCheckResult check(JobDefinition job, JobExecutionContext context);
}
```

#### 5.3 JobExecutionContext

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/JobExecutionContext.java`

```java
public record JobExecutionContext(
    Instant timestamp,
    Map<String, String> partitionHints  // e.g. {"date": "2026-08-11"}
) {
    public static JobExecutionContext of(Instant timestamp) {
        return new JobExecutionContext(timestamp, Map.of());
    }
}
```

#### 5.4 DefaultJobDependencyGuard (Implementation)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DefaultJobDependencyGuard.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultJobDependencyGuard implements JobDependencyGuard {
    private final ManifestReader manifestReader;
    private final Map<DependencyCondition, DependencyConditionEvaluator> evaluators;

    @Autowired
    public DefaultJobDependencyGuard(
            ManifestReader manifestReader,
            List<DependencyConditionEvaluator> evaluatorList) {
        this.manifestReader = manifestReader;
        this.evaluators = evaluatorList.stream()
            .collect(Collectors.toMap(
                DependencyConditionEvaluator::getCondition,
                Function.identity()));
    }

    @Override
    public DependencyCheckResult check(JobDefinition job, JobExecutionContext context) {
        String dependencyMode = getDependencyMode(job);

        if ("DOCUMENTATION_ONLY".equals(dependencyMode)) {
            log.debug("Job [{}] has DOCUMENTATION_ONLY dependency mode, skipping checks",
                job.getId());
            return DependencyCheckResult.ready();
        }

        List<DatasetDependency> dependencies = parseDependencies(job, context);

        if (dependencies.isEmpty()) {
            log.debug("Job [{}] has no dataset dependencies", job.getId());
            return DependencyCheckResult.ready();
        }

        log.debug("Checking {} dataset dependencies for job [{}]",
            dependencies.size(), job.getId());

        for (DatasetDependency dep : dependencies) {
            DependencyCheckResult result = checkDependency(dep);
            if (result.isBlocked()) {
                log.warn("Job [{}] blocked by dependency: {}", job.getId(), result);
                return result;
            }
        }

        log.debug("All dependencies satisfied for job [{}]", job.getId());
        return DependencyCheckResult.ready();
    }

    private DependencyCheckResult checkDependency(DatasetDependency dep) {
        DatasetRef ref = dep.datasetRef();

        // Read manifest once for all conditions
        Optional<DatasetManifest> manifest;
        try {
            manifest = manifestReader.readManifest(ref);
        } catch (ManifestReadException e) {
            log.error("Failed to read manifest for {}: {}", ref, e.getMessage(), e);
            return DependencyCheckResult.error(ref, e.getMessage());
        }

        // Evaluate each condition in order
        for (DependencyCondition condition : dep.conditions()) {
            DependencyConditionEvaluator evaluator = evaluators.get(condition);
            if (evaluator == null) {
                log.warn("No evaluator registered for condition: {}", condition);
                continue;
            }

            DependencyCheckResult result = evaluator.evaluate(ref, manifest, dep.parameters());
            if (result.isBlocked()) {
                return result;
            }
        }

        return DependencyCheckResult.ready();
    }

    private String getDependencyMode(JobDefinition job) {
        if (job.getConfigJson() == null) {
            return "DOCUMENTATION_ONLY";
        }
        return (String) job.getConfigJson()
            .getOrDefault("dependencyMode", "DOCUMENTATION_ONLY");
    }

    private List<DatasetDependency> parseDependencies(
            JobDefinition job, JobExecutionContext context) {

        if (job.getConfigJson() == null) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<String> datasetNames = (List<String>) job.getConfigJson()
            .getOrDefault("dependsOnDatasets", List.of());

        if (datasetNames.isEmpty()) {
            return List.of();
        }

        // Build partition from context hints + job config
        Map<String, String> partition = buildPartition(job, context);

        return datasetNames.stream()
            .map(name -> DatasetDependency.of(name, partition))
            .toList();
    }

    private Map<String, String> buildPartition(
            JobDefinition job, JobExecutionContext context) {

        Map<String, String> partition = new HashMap<>();

        // Add context hints
        partition.putAll(context.partitionHints());

        // Add job-specific partition fields from config
        if (job.getConfigJson() != null) {
            String exchange = (String) job.getConfigJson().get("exchange");
            if (exchange != null) {
                partition.put("exchange", exchange);
            }

            String timeframe = (String) job.getConfigJson().get("timeframe");
            if (timeframe != null) {
                partition.put("timeframe", timeframe);
            }
        }

        return partition;
    }
}
```

### Phase 6: Blocked Job Tracking

#### 6.1 BlockedJob (Entity)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/BlockedJob.java`

```java
@Entity
@Table(name = "blocked_jobs")
@Getter
@Setter
public class BlockedJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_definition_id", nullable = false)
    private UUID jobDefinitionId;

    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    @Column(name = "blocked_reason", columnDefinition = "text")
    private String blockedReason;

    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;
}
```

#### 6.2 BlockedJobRepository

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/BlockedJobRepository.java`

```java
@Repository
public interface BlockedJobRepository extends JpaRepository<BlockedJob, UUID> {
    Optional<BlockedJob> findByJobDefinitionId(UUID jobDefinitionId);

    @Modifying
    @Query("DELETE FROM BlockedJob b WHERE b.jobDefinitionId = :jobDefinitionId")
    void deleteByJobDefinitionId(@Param("jobDefinitionId") UUID jobDefinitionId);
}
```

#### 6.3 BlockedJobTracker (Service)

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/services/BlockedJobTracker.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class BlockedJobTracker {
    private final BlockedJobRepository repository;

    // Exponential backoff: 30s, 1m, 2m, 5m (max)
    private static final List<Duration> RETRY_BACKOFF = List.of(
        Duration.ofSeconds(30),
        Duration.ofMinutes(1),
        Duration.ofMinutes(2),
        Duration.ofMinutes(5)
    );

    public void recordBlocked(JobDefinition job, String reason, Instant now) {
        Optional<BlockedJob> existing = repository.findByJobDefinitionId(job.getId());

        if (existing.isPresent()) {
            BlockedJob blocked = existing.get();
            blocked.setBlockedReason(reason);
            blocked.setRetryCount(blocked.getRetryCount() + 1);
            blocked.setNextRetryAt(calculateNextRetry(blocked.getRetryCount(), now));
            blocked.setLastCheckAt(now);
            repository.save(blocked);

            log.debug("Updated blocked job [{}] retry_count={} next_retry={}",
                job.getId(), blocked.getRetryCount(), blocked.getNextRetryAt());
        } else {
            BlockedJob blocked = new BlockedJob();
            blocked.setJobDefinitionId(job.getId());
            blocked.setBlockedAt(now);
            blocked.setBlockedReason(reason);
            blocked.setRetryCount(1);
            blocked.setNextRetryAt(calculateNextRetry(1, now));
            blocked.setLastCheckAt(now);
            repository.save(blocked);

            log.info("Job [{}] blocked: {}", job.getId(), reason);
        }
    }

    public void clearBlocked(UUID jobDefinitionId) {
        repository.deleteByJobDefinitionId(jobDefinitionId);
        log.debug("Cleared blocked state for job [{}]", jobDefinitionId);
    }

    public boolean shouldRetry(JobDefinition job, Instant now) {
        Optional<BlockedJob> blocked = repository.findByJobDefinitionId(job.getId());
        if (blocked.isEmpty()) {
            return true;  // not blocked, can try
        }

        Instant nextRetry = blocked.get().getNextRetryAt();
        return nextRetry != null && now.isAfter(nextRetry);
    }

    private Instant calculateNextRetry(int retryCount, Instant now) {
        int index = Math.min(retryCount - 1, RETRY_BACKOFF.size() - 1);
        Duration backoff = RETRY_BACKOFF.get(index);
        return now.plus(backoff);
    }
}
```

### Phase 7: Scheduler Integration

#### 7.1 Updated JobScheduler

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/JobScheduler.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {
    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobProducerRegistry jobProducerRegistry;
    private final SchedulerClaimService schedulerClaimService;
    private final JobDependencyGuard dependencyGuard;  // ← NEW
    private final BlockedJobTracker blockedJobTracker;  // ← NEW

    @Scheduled(fixedDelayString = "${app.scheduler.global.fixedDelayString:30000}")
    public void scan() {
        Instant now = Instant.now();
        List<SchedulerClaim> claims = schedulerClaimService.claimDueJobs(now);

        if (claims.isEmpty()) {
            log.debug("No due jobs at {}", now);
            return;
        }

        log.info("Claimed {} due job(s)", claims.size());

        for (SchedulerClaim claim : claims) {
            JobDefinition job = jobDefinitionRepository.findById(claim.jobDefinitionId()).orElse(null);
            if (job == null) {
                log.warn("Claimed job definition disappeared: {}", claim.jobDefinitionId());
                continue;
            }

            // ==========================================
            // NEW: Dependency Guard Check
            // ==========================================
            if (!blockedJobTracker.shouldRetry(job, now)) {
                log.debug("Job [{}] in backoff period, skipping", job.getId());
                schedulerClaimService.releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
                continue;
            }

            JobExecutionContext context = JobExecutionContext.of(now);
            DependencyCheckResult depResult = dependencyGuard.check(job, context);

            if (depResult.isBlocked()) {
                log.warn("Job [{}] dependencies not satisfied: {}", job.getId(), depResult.getReason().orElse("unknown"));
                blockedJobTracker.recordBlocked(job, depResult.getReason().orElse("unknown"), now);
                schedulerClaimService.releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
                continue;
            }

            // Dependencies satisfied - clear any blocked state
            blockedJobTracker.clearBlocked(job.getId());
            // ==========================================

            log.info("Dispatching job [{}] type [{}] source [{}]",
                job.getId(), job.getJobType(), job.getSource());

            try {
                jobProducerRegistry.getProducer(job.getJobType()).prepareDispatch(job, claim, now);
            } catch (Exception e) {
                log.error("Failed to dispatch job [{}]: {}", job.getId(), e.getMessage(), e);
            }
        }
    }
}
```

### Phase 8: Configuration

#### 8.1 Application Properties

**File**: `apps/core/src/main/resources/application.yml`

```yaml
app:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket: ${MINIO_BUCKET:omni}

  scheduler:
    instance-id: ${SCHEDULER_INSTANCE_ID:platform-scheduler-${random.uuid}}
    zone: Asia/Ho_Chi_Minh
    global:
      fixed-delay-string: 30000
    claim:
      lease-duration: PT2M
      batch-size: 10
    dependency:
      manifest-cache-ttl: PT1M
      manifest-cache-size: 1000
```

#### 8.2 Dependency Guard Config

**File**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/config/DependencyGuardConfig.java`

```java
@Configuration
public class DependencyGuardConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }

    @Bean
    public ManifestReader manifestReader(
            MinioClient minioClient,
            @Value("${app.minio.bucket}") String bucket,
            @Value("${app.scheduler.dependency.manifest-cache-ttl:PT1M}") Duration cacheTtl,
            @Value("${app.scheduler.dependency.manifest-cache-size:1000}") int cacheSize) {

        MinioManifestReader baseReader = new MinioManifestReader(minioClient, bucket);

        Cache<DatasetRef, Optional<DatasetManifest>> cache = Caffeine.newBuilder()
            .expireAfterWrite(cacheTtl)
            .maximumSize(cacheSize)
            .build();

        return new CachedManifestReader(baseReader, cache);
    }
}
```

### Phase 9: Database Migration

#### 9.1 Create Blocked Jobs Table

**File**: `database/migrations/V7__create_blocked_jobs_table.sql`

```sql
CREATE TABLE IF NOT EXISTS blocked_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_definition_id UUID NOT NULL,
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    blocked_reason TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    last_check_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_blocked_jobs_job_definition
        FOREIGN KEY (job_definition_id)
        REFERENCES job_definitions(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_blocked_jobs_job_definition_id
    ON blocked_jobs(job_definition_id);

CREATE INDEX idx_blocked_jobs_next_retry_at
    ON blocked_jobs(next_retry_at)
    WHERE next_retry_at IS NOT NULL;
```

### Phase 10: Testing Strategy

#### 10.1 Unit Tests

**ManifestReaderTest**

```java
@Test
void readManifest_returnsEmpty_whenManifestDoesNotExist() {
    DatasetRef ref = DatasetRef.of("eod", Map.of("exchange", "HOSE"));
    Optional<DatasetManifest> result = manifestReader.readManifest(ref);
    assertThat(result).isEmpty();
}

@Test
void readManifest_returnsManifest_whenExists() {
    // Given: manifest exists in MinIO
    DatasetRef ref = DatasetRef.of("eod", Map.of("exchange", "HOSE"));

    // When
    Optional<DatasetManifest> result = manifestReader.readManifest(ref);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().dataset()).isEqualTo("eod");
    assertThat(result.get().status()).isEqualTo("READY");
}
```

**DependencyGuardTest**

```java
@Test
void check_returnsReady_whenDependencyModeIsDocumentationOnly() {
    JobDefinition job = job(Map.of("dependencyMode", "DOCUMENTATION_ONLY"));
    DependencyCheckResult result = guard.check(job, context(now));
    assertThat(result.isReady()).isTrue();
}

@Test
void check_returnsMissing_whenManifestDoesNotExist() {
    JobDefinition job = job(Map.of(
        "dependencyMode", "ENFORCED",
        "dependsOnDatasets", List.of("eod")
    ));

    when(manifestReader.readManifest(any())).thenReturn(Optional.empty());

    DependencyCheckResult result = guard.check(job, context(now));
    assertThat(result.getStatus()).isEqualTo(DependencyStatus.MISSING);
}

@Test
void check_returnsNotReady_whenManifestStatusIsProcessing() {
    DatasetManifest manifest = manifestBuilder()
        .status("PROCESSING")
        .build();

    JobDefinition job = job(Map.of(
        "dependencyMode", "ENFORCED",
        "dependsOnDatasets", List.of("eod")
    ));

    when(manifestReader.readManifest(any())).thenReturn(Optional.of(manifest));

    DependencyCheckResult result = guard.check(job, context(now));
    assertThat(result.getStatus()).isEqualTo(DependencyStatus.NOT_READY);
}

@Test
void check_returnsReady_whenAllDependenciesSatisfied() {
    DatasetManifest manifest = manifestBuilder()
        .status("READY")
        .rowCount(1000)
        .build();

    JobDefinition job = job(Map.of(
        "dependencyMode", "ENFORCED",
        "dependsOnDatasets", List.of("eod")
    ));

    when(manifestReader.readManifest(any())).thenReturn(Optional.of(manifest));

    DependencyCheckResult result = guard.check(job, context(now));
    assertThat(result.isReady()).isTrue();
}
```

**CurrentInputsEvaluatorTest**

```java
@Test
void evaluate_returnsReady_whenInputVersionsMatch() {
    DatasetManifest downstream = manifestBuilder()
        .dataset("indicators")
        .inputs(List.of(new DatasetInput("eod", Map.of(), "sha256:abc123")))
        .build();

    DatasetManifest upstream = manifestBuilder()
        .dataset("eod")
        .dataVersion("sha256:abc123")
        .build();

    when(manifestReader.readManifest(any())).thenReturn(Optional.of(upstream));

    DependencyCheckResult result = evaluator.evaluate(
        DatasetRef.of("indicators"),
        Optional.of(downstream),
        Map.of()
    );

    assertThat(result.isReady()).isTrue();
}

@Test
void evaluate_returnsInputVersionMismatch_whenVersionsDiffer() {
    DatasetManifest downstream = manifestBuilder()
        .dataset("indicators")
        .inputs(List.of(new DatasetInput("eod", Map.of(), "sha256:old")))
        .build();

    DatasetManifest upstream = manifestBuilder()
        .dataset("eod")
        .dataVersion("sha256:new")
        .build();

    when(manifestReader.readManifest(any())).thenReturn(Optional.of(upstream));

    DependencyCheckResult result = evaluator.evaluate(
        DatasetRef.of("indicators"),
        Optional.of(downstream),
        Map.of()
    );

    assertThat(result.getStatus()).isEqualTo(DependencyStatus.INPUT_VERSION_MISMATCH);
}
```

#### 10.2 Integration Tests

**JobSchedulerIntegrationTest**

```java
@SpringBootTest
@Testcontainers
class JobSchedulerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("omni_test");

    @Container
    static GenericContainer<?> minio = new GenericContainer<>("minio/minio:latest")
        .withExposedPorts(9000)
        .withEnv("MINIO_ROOT_USER", "minioadmin")
        .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
        .withCommand("server /data");

    @Test
    void scheduler_defersJob_whenDependencyMissing() {
        // Given: job with ENFORCED dependency on missing dataset
        JobDefinition job = saveJob(Map.of(
            "dependencyMode", "ENFORCED",
            "dependsOnDatasets", List.of("eod")
        ));
        job.setNextRun(Instant.now().minusSeconds(60));
        repository.save(job);

        // When: scheduler scans
        scheduler.scan();

        // Then: job is not executed, blocked state recorded
        List<JobExecutionHistory> executions = executionRepository.findAll();
        assertThat(executions).isEmpty();

        Optional<BlockedJob> blocked = blockedJobRepository.findByJobDefinitionId(job.getId());
        assertThat(blocked).isPresent();
        assertThat(blocked.get().getBlockedReason()).contains("does not exist");
    }

    @Test
    void scheduler_executesJob_whenDependencySatisfied() throws Exception {
        // Given: READY manifest exists in MinIO
        uploadManifest("eod", Map.of("exchange", "HOSE"),
            manifestBuilder().status("READY").build());

        JobDefinition job = saveJob(Map.of(
            "dependencyMode", "ENFORCED",
            "dependsOnDatasets", List.of("eod")
        ));
        job.setNextRun(Instant.now().minusSeconds(60));
        repository.save(job);

        // When: scheduler scans
        scheduler.scan();

        // Then: job is executed, no blocked state
        List<JobExecutionHistory> executions = executionRepository.findAll();
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getStatus()).isEqualTo(JobStatus.PENDING);

        Optional<BlockedJob> blocked = blockedJobRepository.findByJobDefinitionId(job.getId());
        assertThat(blocked).isEmpty();
    }
}
```

### Phase 11: Job Definition Updates

Update existing job seeds to add structured dataset dependencies:

**JobDefinitionConfig.java - SYNC_INDICATORS example**

```java
private static final List<JobDefinitionSeed> SYNC_INDICATORS_SEEDS = List.of(
    new JobDefinitionSeed(
        DataSource.ANALYZER,
        List.of(),
        JobType.SYNC_INDICATORS,
        "Sync technical indicators - daily",
        CRON_18_30_WEEKDAYS,
        configWithDependencies(
            Map.of(
                CONFIG_KEY_SECTOR_LEVEL, 2,
                CONFIG_KEY_SECTOR_CODES, List.of(SECTOR_BANK),
                CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                CONFIG_KEY_INDICATOR_SOURCE, CONFIG_KEY_INDICATOR_SOURCE_CLOSE,
                CONFIG_KEY_INDICATORS, SUPPORTED_INDICATORS
            ),
            List.of(JobType.SYNC_STOCK_PRICE.name()),  // job dependency (operational)
            List.of(DATASET_EOD),                       // dataset dependency (hard)
            List.of(DATASET_INDICATORS)                 // produces
        )
    )
);
```

For migration, add `dependencyMode` to config:

```java
private static Map<String, Object> configWithDependencies(
        Map<String, Object> config,
        List<String> dependsOnJobs,
        List<String> dependsOnDatasets,
        List<String> producesDatasets) {
    return Map.copyOf(Map.of(
        "dependencyMode", "DOCUMENTATION_ONLY",  // Start with documentation mode
        "dependsOnJobs", dependsOnJobs,
        "dependsOnDatasets", dependsOnDatasets,
        "producesDatasets", producesDatasets,
        // ... merge with config
    ));
}
```

### Phase 12: Documentation Updates

#### 12.1 AGENTS.md - Job Dependency Rule

Add after Dataset Manifest Rule:

````markdown
## Job Dependency Rule

Cron timing gaps are scheduling hints, not dependency guarantees.

Hard data dependencies use centralized dataset manifests:

```text
dependsOnDatasets -> hard readiness/currentness
dependsOnJobs     -> operational/traceability dependency
```
````

A due job whose dataset dependency is missing/stale is `BLOCKED`/deferred, not a failed job execution.

When a downstream dataset must reflect the current upstream version, compare its recorded `inputs[].dataVersion` with the current upstream manifest `dataVersion`.

Required workflow:

1. Use `dependencyMode: "ENFORCED"` to activate runtime checks.
2. Specify `dependsOnDatasets: ["eod", "indicators"]` for hard dependencies.
3. Platform scheduler checks manifests before claiming jobs.
4. BLOCKED jobs defer with exponential backoff (30s → 1m → 2m → 5m max).
5. No FAILED execution history is created for blocked dependencies.

Never assume cron gaps guarantee upstream completion. Use manifest-based dependencies for correctness.

````

#### 12.2 docs/flows/job-execution.md

Update flow diagram to include dependency guard:

```mermaid
graph TD
    A[Scheduler Scan] --> B[Claim Due Jobs]
    B --> C{For Each Claim}
    C --> D[Load Job Definition]
    D --> E[Dependency Guard Check]
    E --> F{Dependencies Met?}
    F -->|BLOCKED| G[Record Blocked State]
    G --> H[Release Claim]
    H --> I[Log Warning + Continue]
    F -->|READY| J[Clear Blocked State]
    J --> K[Prepare Dispatch]
    K --> L[Create Execution History]
    L --> M[Enqueue Messages]
    M --> N[Update Next Run]
````

Add section:

```markdown
## Dependency Guard

Before creating a job execution, the scheduler checks dataset dependencies:

1. Parse `dependsOnDatasets` from job config
2. For each dataset: read manifest from S3/R2
3. Evaluate conditions: EXISTS, READY, CURRENT_INPUTS
4. If any condition fails: record blocked state, release claim
5. If all pass: proceed with execution

Blocked jobs use exponential backoff retry:

- First block: retry after 30s
- Second block: retry after 1m
- Third block: retry after 2m
- Fourth+ block: retry after 5m (max)

Blocked jobs do NOT create FAILED execution history entries.
```

## Implementation Sequence

### Week 1: Foundation

- [x] Day 1: DatasetRef, DependencyCondition, DependencyStatus, DependencyCheckResult
- [ ] Day 2: DatasetManifest Java models (DatasetManifest, ColumnMetadata, DatasetInput)
- [ ] Day 3: ManifestReader interface + MinioManifestReader implementation
- [ ] Day 4: CachedManifestReader + ManifestReadException
- [ ] Day 5: Unit tests for manifest reader

### Week 2: Condition Evaluators

- [ ] Day 1: DependencyConditionEvaluator interface + ExistsEvaluator
- [ ] Day 2: ReadyEvaluator + MinRowCountEvaluator
- [ ] Day 3: CurrentInputsEvaluator (with manifest reader integration)
- [ ] Day 4: Unit tests for all evaluators
- [ ] Day 5: Integration tests for condition evaluation

### Week 3: Dependency Guard

- [ ] Day 1: DatasetDependency, JobExecutionContext, JobDependencyGuard interface
- [ ] Day 2: DefaultJobDependencyGuard implementation
- [ ] Day 3: Unit tests for dependency guard
- [ ] Day 4: BlockedJob entity + repository + tracker
- [ ] Day 5: Integration tests for blocked job tracking

### Week 4: Scheduler Integration

- [ ] Day 1: Update JobScheduler with guard integration
- [ ] Day 2: DependencyGuardConfig + application.yml
- [ ] Day 3: Database migration for blocked_jobs table
- [ ] Day 4: End-to-end integration tests
- [ ] Day 5: Performance testing + cache tuning

### Week 5: Documentation & Migration

- [ ] Day 1: Update job definition seeds with typed dependencies
- [ ] Day 2: Update AGENTS.md + docs/flows/job-execution.md
- [ ] Day 3: Create migration guide for existing jobs
- [ ] Day 4: Enable ENFORCED mode for pilot jobs (SYNC_INDICATORS, SYNC_SIGNALS)
- [ ] Day 5: Monitor production, gather metrics

## Success Criteria

- [ ] All unit tests passing (>90% coverage for new code)
- [ ] Integration tests cover BLOCKED and READY scenarios
- [ ] Scheduler defers jobs with missing dependencies without false failures
- [ ] CURRENT_INPUTS detects stale lineage correctly
- [ ] Blocked jobs use exponential backoff
- [ ] Manifest cache reduces S3 reads by >80%
- [ ] No performance regression in scheduler scan loop (<100ms overhead per claim)
- [ ] Documentation updated in AGENTS.md, docs/flows/job-execution.md
- [ ] At least 2 pilot jobs running in ENFORCED mode successfully

## Risk Mitigation

### MinIO Connection Failures

**Risk**: Manifest reader failures block all jobs
**Mitigation**:

- Wrap manifest reads in try-catch with DependencyStatus.ERROR
- Use cached reads to reduce MinIO load
- Add circuit breaker for repeated failures
- Fallback to DOCUMENTATION_ONLY mode on persistent errors

### Performance Impact

**Risk**: Dependency checks slow down scheduler scan
**Mitigation**:

- Manifest cache with 60s TTL
- Read manifests concurrently (CompletableFuture)
- Limit evaluator execution time (timeout per check)
- Async blocked job tracking (non-blocking DB writes)

### False Blocking

**Risk**: Jobs blocked unnecessarily due to manifest delays
**Mitigation**:

- READY-last manifest semantics guarantee validity
- Exponential backoff prevents spam
- Detailed blocked_reason logging for debugging
- DOCUMENTATION_ONLY mode for gradual rollout

### Migration Complexity

**Risk**: Existing jobs break when enabling ENFORCED mode
**Mitigation**:

- Keep all jobs in DOCUMENTATION_ONLY initially
- Opt-in per job via config change
- Pilot with 2-3 jobs before broad rollout
- Clear migration guide with rollback steps

## Next Steps After Implementation

1. **Monitoring Dashboard**: Build Internal Tools view for blocked jobs
2. **Alerting**: Notify when jobs blocked >30 minutes
3. **Dataset-Ready Events**: Trigger immediate re-check on manifest publish
4. **Advanced Conditions**: Add PARTITION_MATCH, MAX_FRESHNESS_LAG evaluators
5. **Multi-Dataset Conditions**: AND/OR logic for complex dependencies
6. **Protobuf Integration**: Use DatasetRef/DatasetOutput from contracts proto

## Files to Create

### Java Classes (18 files)

1. `DependencyCondition.java` ✅
2. `DependencyStatus.java` ✅
3. `DatasetRef.java` ✅
4. `DependencyCheckResult.java` ✅
5. `DatasetManifest.java`
6. `ColumnMetadata.java`
7. `DatasetInput.java`
8. `ManifestReader.java`
9. `MinioManifestReader.java`
10. `CachedManifestReader.java`
11. `ManifestReadException.java`
12. `DependencyConditionEvaluator.java`
13. `ExistsEvaluator.java`
14. `ReadyEvaluator.java`
15. `MinRowCountEvaluator.java`
16. `CurrentInputsEvaluator.java`
17. `DatasetDependency.java`
18. `JobExecutionContext.java`
19. `JobDependencyGuard.java`
20. `DefaultJobDependencyGuard.java`
21. `BlockedJob.java`
22. `BlockedJobRepository.java`
23. `BlockedJobTracker.java`
24. `DependencyGuardConfig.java`

### Test Classes (8 files)

1. `DatasetRefTest.java`
2. `ManifestReaderTest.java`
3. `ExistsEvaluatorTest.java`
4. `ReadyEvaluatorTest.java`
5. `CurrentInputsEvaluatorTest.java`
6. `DefaultJobDependencyGuardTest.java`
7. `BlockedJobTrackerTest.java`
8. `JobSchedulerIntegrationTest.java`

### Configuration/Migration (2 files)

1. `V7__create_blocked_jobs_table.sql`
2. Update `application.yml`

### Documentation (3 files)

1. Update `AGENTS.md`
2. Update `docs/flows/job-execution.md`
3. Update `docs/data/data-lake.md` (if needed)

**Total**: ~33 files to create/update

## Dependency Graph

```
DatasetRef, DependencyCondition, DependencyStatus
    ↓
DependencyCheckResult
    ↓
DatasetManifest models
    ↓
ManifestReader (interface + MinIO impl + cached)
    ↓
DependencyConditionEvaluator (interface + 5 impls)
    ↓
JobDependencyGuard (interface + default impl)
    ↓
BlockedJob + tracker
    ↓
JobScheduler integration
```

## Conclusion

This implementation transforms the Omni scheduler from cron-only timing to manifest-based dependency enforcement. By using dataset manifests as the source of truth, jobs coordinate across machines, detect stale lineage, and defer execution without false failures.

The phased approach allows incremental migration: all jobs start in `DOCUMENTATION_ONLY` mode, then opt-in to `ENFORCED` mode job-by-job. This reduces risk while unlocking deterministic, lineage-aware execution.
