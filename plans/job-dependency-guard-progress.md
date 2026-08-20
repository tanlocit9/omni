# Job Dependency Guard Implementation Progress

## Status: In Progress (Phases 1-5 Complete)

Implementation started: 2026-08-18
Last updated: 2026-08-18T13:52:28Z

---

## Completed Phases

### ✅ Phase 1: Foundational Abstractions (Complete)

Created core value objects and enums:

- [`DatasetRef.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DatasetRef.java) - Logical reference to dataset partition
- [`DependencyCondition.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyCondition.java) - Enum of 7 check conditions
- [`DependencyStatus.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyStatus.java) - Result status enum
- [`DependencyCheckResult.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DependencyCheckResult.java) - Encapsulates check results with factory methods

### ✅ Phase 2: Dataset Manifest Java Models (Complete)

Created Java records mirroring Python manifest models:

- [`ColumnMetadata.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/models/ColumnMetadata.java) - Column metadata record
- [`DatasetInput.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/models/DatasetInput.java) - Upstream lineage record
- [`DatasetManifest.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/models/DatasetManifest.java) - Complete manifest model with helper methods

### ✅ Phase 3: ManifestReader Infrastructure (Complete)

Implemented manifest reading from MinIO/S3:

- [`ManifestReadException.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/ManifestReadException.java) - Exception for I/O errors
- [`ManifestReader.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/ManifestReader.java) - Reader interface
- [`MinioManifestReader.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/MinioManifestReader.java) - MinIO implementation
- [`CachedManifestReader.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/CachedManifestReader.java) - Caffeine cache decorator (60s TTL)

### ✅ Phase 4: Condition Evaluators (Complete)

Implemented evaluator strategy pattern:

- [`ConditionEvaluator.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/ConditionEvaluator.java) - Strategy interface
- [`EvaluationContext.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/EvaluationContext.java) - Shared evaluation context
- [`ExistsEvaluator.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/ExistsEvaluator.java) - Checks manifest existence
- [`ReadyEvaluator.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/ReadyEvaluator.java) - Checks READY status
- [`MinRowCountEvaluator.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/MinRowCountEvaluator.java) - Checks row count threshold
- [`CurrentInputsEvaluator.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/evaluators/CurrentInputsEvaluator.java) - Validates upstream lineage

### ✅ Phase 5: JobDependencyGuard Service (Complete)

Implemented core guard logic:

- [`JobExecutionContext.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/JobExecutionContext.java) - Job execution context record
- [`DatasetDependency.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DatasetDependency.java) - Dependency declaration model
- [`JobDependencyGuard.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/JobDependencyGuard.java) - Guard interface with GuardResult
- [`DefaultJobDependencyGuard.java`](apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/DefaultJobDependencyGuard.java) - Complete guard implementation

---

## Pending Phases

### ⏳ Phase 6: Blocked Job Tracking

Database-backed tracking of deferred jobs:

- [ ] `BlockedJob.java` - JPA entity
- [ ] `BlockedJobRepository.java` - Spring Data repository
- [ ] `BlockedJobTracker.java` - Service for tracking blocked jobs with exponential backoff

### ⏳ Phase 7: Scheduler Integration

Wire guard into job execution flow:

- [ ] Update `JobScheduler.java` to call guard before dispatch
- [ ] Add guard bean configuration
- [ ] Add MinIO client bean configuration

### ⏳ Phase 8: Configuration

Application configuration:

- [ ] Update `application.yml` with dependency guard settings
- [ ] Add MinIO connection properties
- [ ] Add feature flags (guard enabled, default mode)

### ⏳ Phase 9: Database Migration

Schema changes:

- [ ] `V7__create_blocked_jobs_table.sql` - Migration script

### ⏳ Phase 10: Testing

Comprehensive test coverage:

- [ ] Unit tests for all evaluators
- [ ] Unit tests for guard logic
- [ ] Integration tests for scheduler flow
- [ ] Test fixtures with sample manifests

### ⏳ Phase 11: Job Definition Updates

Add typed dependencies to existing jobs:

- [ ] Update `JobDefinitionConfig.java` seeds
- [ ] Add `dependsOnDatasets` to job configs
- [ ] Set appropriate dependency modes

### ⏳ Phase 12: Documentation

Update repository guidance:

- [ ] Update `AGENTS.md` with job dependency rules
- [ ] Update `docs/flows/job-execution.md`
- [ ] Create migration guide
- [ ] Update job dependency plan with completion status

---

## Architecture Summary

### Components Created (16 files)

**Core Abstractions (4 files):**

- DatasetRef, DependencyCondition, DependencyStatus, DependencyCheckResult

**Manifest Models (3 files):**

- ColumnMetadata, DatasetInput, DatasetManifest

**Manifest Reading (4 files):**

- ManifestReadException, ManifestReader, MinioManifestReader, CachedManifestReader

**Condition Evaluation (5 files):**

- ConditionEvaluator, EvaluationContext, ExistsEvaluator, ReadyEvaluator, MinRowCountEvaluator, CurrentInputsEvaluator

**Guard Service (4 files):**

- JobExecutionContext, DatasetDependency, JobDependencyGuard, DefaultJobDependencyGuard

### Key Design Decisions

1. **Strategy Pattern for Evaluators**: Each condition (EXISTS, READY, etc.) has its own evaluator class implementing `ConditionEvaluator`
2. **Caffeine Cache**: 60-second TTL on manifest reads to reduce MinIO I/O
3. **DOCUMENTATION_ONLY Mode**: Safe migration path - log warnings without blocking
4. **Immutable Records**: Thread-safe value objects throughout
5. **Factory Methods**: Clean API for creating check results
6. **Lombok @Getter**: DatasetRef uses Lombok getters, not record accessors

### Integration Points

- **ManifestReader** reads JSON manifests from MinIO at `_metadata/datasets/{dataset}/{partition}.json`
- **JobDependencyGuard** parses `dependsOnDatasets` from job config JSON
- **Scheduler** will call guard before dispatching jobs
- **BlockedJobTracker** (pending) will manage deferred jobs with exponential backoff

---

## Next Steps

1. Implement Phase 6: BlockedJob entity and tracking service
2. Integrate guard into JobScheduler (Phase 7)
3. Add configuration and database migration (Phases 8-9)
4. Write comprehensive tests (Phase 10)
5. Update job definitions with dependencies (Phase 11)
6. Complete documentation updates (Phase 12)

---

## Notes

- All Java compilation errors resolved (Lombok getter methods fixed)
- Core dependency checking logic is complete and ready for integration
- Missing evaluators (PARTITION_MATCH, SUPPORTED_SCHEMA_VERSION, MAX_FRESHNESS_LAG) marked as TODO
- Python manifest infrastructure already deployed and working
