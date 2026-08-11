# Portable Docker Deployment Implementation Plan

## Goal

Make Omni compute disposable and portable.

A host should contain only application runtime plus short-lived/local operational state. Analytical datasets and PostgreSQL backups must live in centralized S3-compatible object storage so a new machine can be provisioned by pulling containers and restoring the latest database backup.

Production does **not** migrate MinIO data volumes between machines.

## Outcome

After this phase:

- Platform, Analyzer, Ingestor and Internal Tools are published as immutable Docker images;
- a new Linux machine can run Omni using Docker + Compose + environment configuration only;
- Parquet datasets and `_metadata` manifests live in centralized AWS S3 or Cloudflare R2;
- local MinIO is development-only and is not required in the cloud deployment profile;
- PostgreSQL remains local to the compute host but is backed up automatically to S3/R2;
- a fresh host can restore PostgreSQL from the latest verified object-storage backup;
- Kafka is treated as replay/retry transport state and does not need long-term machine migration;
- developers can run the same containers locally while reading the same centralized datasets with scoped credentials;
- only HTTP/HTTPS application entry points are exposed publicly by default.

## Target Architecture

```text
                         Central Object Storage
                         AWS S3 / Cloudflare R2
                    +-----------------------------+
                    | Parquet datasets            |
                    | _metadata manifests         |
                    | PostgreSQL backups          |
                    +--------------+--------------+
                                   ^
                                   | HTTPS / S3 API
                                   |
+----------------------------------------------------------------+
| Replaceable Compute Host                                        |
|                                                                |
| reverse proxy / internal-tools                                 |
| platform                                                       |
| analyzer                                                       |
| ingestor                                                       |
| kafka                                                          |
| postgres                                                       |
|                                                                |
| local persistent state: postgres + bounded Kafka only          |
+----------------------------------------------------------------+
```

Machine replacement becomes:

```text
pull images
   -> configure object-storage credentials
   -> start postgres
   -> restore latest DB backup
   -> start Kafka + Omni services
   -> validate dataset manifests
```

No Parquet copy/mirror step is required.

## Current State

Omni already contains:

```text
apps/core/Dockerfile
apps/analyzer/Dockerfile
apps/ingestor/Dockerfile
docker-compose.infra.yaml
docker-compose.services.yaml
docker-compose.yaml
```

Current Compose remains development-oriented because:

- application images are built on the host;
- source is bind-mounted into containers;
- Analyzer uses Uvicorn reload mode;
- pgAdmin is always available;
- MinIO runs locally by default;
- Kafka uses `/tmp` storage;
- development credentials are embedded as defaults;
- infra ports are exposed to the host.

## Deployment Principle

Treat compute as replaceable.

Persist only what cannot be reconstructed cheaply:

```text
AUTHORITATIVE
  S3/R2 Parquet datasets
  S3/R2 dataset manifests
  S3/R2 PostgreSQL backups

LOCAL / REPLACEABLE
  Docker images
  application containers
  Kafka offsets/logs after jobs are drained
  PostgreSQL volume after a verified remote backup exists
```

Do not introduce filesystem migration as a normal deployment procedure.

## Central Object Storage

### Preferred V1: Cloudflare R2

R2 is the preferred shared-development target when minimizing cost because it is S3-compatible and has no Internet egress fee.

Use one bucket initially, for example:

```text
omni-data
```

Logical layout:

```text
omni-data/
├── datasets/
│   ├── symbols/
│   ├── eod/
│   ├── indicators/
│   ├── signals/
│   ├── features/
│   ├── research/
│   └── intraday/
├── _metadata/
│   ├── catalog.json
│   └── datasets/
└── backups/
    └── postgres/
```

If changing the existing `stock-data` bucket prefix would create unnecessary migration work, retain the current dataset paths and add only the backup prefix.

### AWS S3 alternative

Use AWS S3 when:

- the compute environment is primarily AWS;
- IAM roles are preferred over static S3 credentials;
- AWS lifecycle/storage-class integration is useful;
- future Athena/Glue/AWS analytics integration becomes relevant.

Storage abstraction must remain S3-compatible so choosing R2 or S3 is configuration, not application code.

## Environment Configuration

Use one common S3-compatible contract:

```text
OBJECT_STORAGE_PROVIDER=R2|S3
S3_ENDPOINT_URL=...
S3_REGION=...
S3_BUCKET_NAME=...
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
```

For AWS EC2, prefer instance-role credentials and omit static keys when the SDK supports the default credential chain.

For R2, use scoped API tokens/credentials.

Do not bake credentials into Docker images.

## Shared Developer Data Model

Developers should be able to pull the same Docker images and inspect the same canonical data without copying datasets locally.

Recommended access model:

```text
canonical datasets
  -> shared read access

production writers
  -> write access to canonical prefixes

developer experiments
  -> write only to dedicated scratch prefix
```

Example:

```text
datasets/...                 canonical shared data
_metadata/...                canonical shared metadata
scratch/{developer}/...      developer-specific outputs
backups/postgres/...         restricted operational backup
```

Do not give every developer write access to canonical datasets just for convenience.

Internal Tools can use presigned URLs or a restricted resolver so browser users never receive long-lived object-storage secrets.

## PostgreSQL Backup Strategy

PostgreSQL is the only important machine-local persistent store in the normal cloud profile.

Add a scheduled job/container that creates a logical backup and uploads it directly to S3/R2.

Suggested flow:

```text
PostgreSQL
    |
    v
pg_dump
    |
    v
gzip/zstd
    |
    +-- checksum
    |
    v
S3/R2 backups/postgres/
```

Suggested object paths:

```text
backups/postgres/prod/2026/08/11/20260811T170000Z.sql.gz
backups/postgres/prod/latest.json
```

`latest.json` should contain:

```json
{
  "version": 1,
  "status": "READY",
  "objectKey": "backups/postgres/prod/2026/08/11/20260811T170000Z.sql.gz",
  "createdAt": "2026-08-11T10:00:00Z",
  "sizeBytes": 1234567,
  "sha256": "...",
  "database": "omni"
}
```

Publish `latest.json` only after upload and checksum verification succeed.

This follows the same READY-manifest principle used by analytical datasets.

## Backup Schedule

Initial V1:

```text
nightly logical backup
+ optional backup before schema-sensitive deployment
```

Suggested retention while staying cost-conscious:

```text
7 daily
4 weekly
3 monthly
```

Tune after measuring actual compressed DB size.

Do not back up Kafka as part of the normal durable backup strategy.

## PostgreSQL Restore

Provide an explicit restore utility as part of the deployment bundle.

Preferred UX:

```bash
docker compose \
  --env-file .env.prod \
  -f docker-compose.prod.yaml \
  --profile restore \
  run --rm db-restore latest
```

Restore flow:

```text
read backups/postgres/<env>/latest.json
   -> download backup
   -> verify SHA-256
   -> require/verify empty target DB or explicit --force
   -> restore
   -> run application migrations
   -> start platform
```

Do not automatically restore on every normal container startup.

A new host should require an explicit bootstrap restore action so accidental rollback cannot happen because of a restart.

## Migration to Another Machine

Normal host migration becomes:

```text
1. stop/disable new scheduled jobs
2. allow active Kafka work to drain
3. create and verify a final PostgreSQL backup to S3/R2
4. stop old compute host
5. install Docker/Compose on new host
6. copy only deployment configuration/secrets
7. docker compose pull
8. start PostgreSQL
9. restore latest/final PostgreSQL backup
10. start Kafka + Platform + workers
11. validate _metadata READY manifests and job state
12. enable scheduling
```

There is no MinIO migration and no Parquet data transfer between hosts.

## Kafka Semantics

Kafka remains local because commands/status messages are operational transport, not analytical truth.

Use bounded retention and a persistent local volume only to survive normal container restarts.

Before deliberate machine migration:

```text
pause producers/scheduler
wait until active work is terminal
take final DB backup
replace host
```

If later realtime tick ingestion requires stronger stream durability during host loss, revisit Kafka separately. Do not solve that future requirement by treating Kafka volume backups as the current migration mechanism.

## Production Compose Profiles

Recommended profiles:

### `cloud`

```text
platform
analyzer
ingestor
kafka
postgres
internal-tools/reverse-proxy
```

No MinIO and no pgAdmin.

### `local`

Adds:

```text
minio
optional pgAdmin
```

for offline/local development.

### `backup`

Provides a one-shot/scheduled PostgreSQL backup container.

### `restore`

Provides the explicit PostgreSQL restore utility.

## Production Images

Publish immutable images to GHCR:

```text
ghcr.io/tanlocit9/omni-platform:<git-sha>
ghcr.io/tanlocit9/omni-analyzer:<git-sha>
ghcr.io/tanlocit9/omni-ingestor:<git-sha>
ghcr.io/tanlocit9/omni-internal-tools:<git-sha>
```

Build for:

```text
linux/amd64
linux/arm64
```

Production Compose must use `image:` rather than `build:`.

## Dockerfile Production Cleanup

### Platform

- keep multi-stage Java build;
- keep non-root runtime;
- remove dev profile default from the image;
- cap JVM memory;
- add health/readiness check.

### Analyzer

- remove `--reload`;
- install runtime dependencies only where practical;
- package `py_common` into the image;
- run as non-root;
- control analytical job concurrency.

### Ingestor

- package `py_common` into the image;
- remove development/test-only dependencies where practical;
- run as non-root.

## Network Exposure

Public host ports:

```text
80
443
```

Private Docker network only:

```text
platform:8080
postgres:5432
kafka:29092
```

There is no MinIO port in the normal cloud profile because S3/R2 is external.

Do not publicly expose PostgreSQL or Kafka to share data with developers.

Shared data access happens through S3/R2 credentials/presigned access and Internal Tools.

## Host Capacity After Externalizing Object Storage

Externalizing Parquet storage lowers disk requirements substantially but does not remove the RAM cost of Kafka, pandas/PyArrow, Spring and PostgreSQL.

### EOD compute

Recommended baseline:

```text
2 vCPU
4 GB RAM
20-30 GB local disk
```

Local disk only needs:

```text
Docker images/layers
PostgreSQL
bounded Kafka logs
container logs
upgrade headroom
```

### Comfortable EOD / early intraday

```text
4 vCPU
8 GB RAM
30-50 GB local disk
```

### Realtime

```text
4+ vCPU
8-16 GB RAM
30-50 GB local disk
external S3/R2 mandatory for archive
```

Do not size the compute disk based on long-term Parquet growth anymore.

## Cloud Storage Cost Direction

For shared development, R2 is currently the more attractive default because its Standard storage free tier includes 10 GB-month/month, 1 million Class A requests/month, 10 million Class B requests/month, and free Internet egress.

AWS S3 is also viable, but traditional new-customer S3 Free Tier has historically been smaller (5 GB Standard plus request allowances for the eligible introductory period). Treat AWS free-tier eligibility as account-specific and verify billing before relying on it.

The application design must not depend on either provider's free tier.

## Dataset Outputs

No new market dataset is created by this deployment phase.

New operational objects:

```text
backups/postgres/<environment>/...
backups/postgres/<environment>/latest.json
```

Existing canonical analytical outputs remain in centralized object storage:

```text
Parquet datasets
_metadata/catalog.json
_metadata/datasets/...
```

## Metadata Outputs

PostgreSQL backup metadata uses an object-storage READY manifest with:

```text
status
objectKey
createdAt
sizeBytes
sha256
database
backupVersion
```

This allows bootstrap/restore code to discover and verify the latest usable backup without listing or downloading all backup objects.

## Algorithm Feature Outputs

No direct algorithm feature output.

The phase improves reproducibility because every developer/compute host can consume the same canonical data lake and feature manifests.

## Algorithms Unlocked

No new algorithm is unlocked directly.

Operationally this enables:

- consistent backtests across machines;
- reproducible feature debugging;
- shared Internal Tools datasets;
- easier horizontal experimentation with the same canonical data inputs.

## Implementation Steps

### Step 1 — Object-storage production profile

- [ ] Make local MinIO optional via Compose profile.
- [ ] Add cloud S3-compatible configuration for R2/AWS S3.
- [ ] Verify Platform/Analyzer/Ingestor use shared endpoint/bucket semantics.
- [ ] Ensure dataset manifests and Parquet are written to the same centralized bucket.

### Step 2 — Production images

- [ ] Remove dev bind mounts and `--reload` from production runtime.
- [ ] Publish Platform/Analyzer/Ingestor images to GHCR.
- [ ] Build amd64 + arm64 where dependencies support both.
- [ ] Use immutable version/SHA tags.

### Step 3 — Production Compose

- [ ] Add `docker-compose.prod.yaml` using registry images.
- [ ] Add `cloud`, `local`, `backup`, and `restore` profiles/overrides as appropriate.
- [ ] Keep Postgres/Kafka private.
- [ ] Expose only reverse proxy/Internal Tools/API entry points.

### Step 4 — Database backup

- [ ] Implement one-shot `db-backup` container/script.
- [ ] `pg_dump` and compress output.
- [ ] Upload directly to S3/R2.
- [ ] Calculate and verify SHA-256.
- [ ] Publish `latest.json` only after a successful upload.
- [ ] Add nightly scheduling.
- [ ] Add retention cleanup.

### Step 5 — Database restore

- [ ] Implement `db-restore latest|<object-key>`.
- [ ] Verify manifest and checksum before restore.
- [ ] Protect non-empty DB unless explicitly forced.
- [ ] Document new-host bootstrap command.

### Step 6 — Shared developer access

- [ ] Define canonical read-only credentials/policy for developers.
- [ ] Define per-developer scratch write prefix when needed.
- [ ] Keep backup prefix restricted.
- [ ] Configure Internal Tools to use presigned/read-only dataset access.

### Step 7 — Migration rehearsal

- [ ] Deploy on a second clean VM/local machine from registry images.
- [ ] Restore DB only from object storage.
- [ ] Start against the existing centralized data bucket.
- [ ] Verify scheduler/job state and dataset READY manifests.
- [ ] Confirm no Parquet/MinIO volume copy was required.

## Acceptance Criteria

- A fresh machine needs no copied data-lake volume.
- The cloud profile runs without MinIO.
- All canonical Parquet and `_metadata` objects remain centralized in S3/R2.
- PostgreSQL backup is uploaded automatically and has a verified READY manifest.
- PostgreSQL can be restored on a clean host from object storage only.
- Kafka does not need to be copied during a planned drained migration.
- Developers can pull the same images and read canonical datasets using scoped credentials.
- Developer writes cannot overwrite canonical datasets unless explicitly authorized.
- Production Compose exposes only intended application ports.
- Target host requires only Docker/Compose plus deployment secrets/configuration.
