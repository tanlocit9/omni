# Portable Docker Deployment Implementation Plan

## Goal

Productionize Omni's existing Docker setup into a portable, pull-and-run deployment bundle that can move between a developer machine, a VPS, AWS EC2, or another cloud VM without rebuilding application code on the target host.

Omni is already Dockerized. The work in this plan is therefore **not** to introduce Docker from scratch; it is to turn the current development-oriented Docker/Compose setup into a production-style portable runtime.

## Outcome

After this phase:

- application images are built once in CI and published to a registry;
- a clean Linux host can start Omni with Docker Compose and an environment file;
- persistent state is isolated from containers and can be backed up/migrated;
- the same bundle supports `linux/amd64` and `linux/arm64` where dependencies allow it;
- local MinIO can be replaced by S3-compatible cloud storage without rebuilding images;
- PostgreSQL and MinIO remain authoritative persistent stores;
- Kafka survives container restarts and is treated as transport state rather than long-term analytical storage;
- infra ports are private by default;
- dev-only behavior such as bind mounts, `--reload`, pgAdmin, and default credentials is removed from the production profile.

## Current State

Current repository already contains:

```text
apps/core/Dockerfile
apps/analyzer/Dockerfile
apps/ingestor/Dockerfile
docker-compose.infra.yaml
docker-compose.services.yaml
docker-compose.yaml
```

The current complete Compose stack includes:

```text
platform
analyzer
ingestor
postgres
kafka
minio
pgadmin
```

Current development-oriented concerns to fix:

- application services are built on the deployment host instead of pulled as immutable images;
- source directories are bind-mounted into application containers;
- analyzer runs Uvicorn with `--reload`;
- pgAdmin is always part of the infra stack;
- PostgreSQL/MinIO use development credentials in Compose defaults;
- Kafka log storage is under `/tmp` and has no persistent volume;
- MinIO image uses `latest` rather than a pinned deployment version;
- application health checks/resource limits are not defined consistently;
- infrastructure ports are published to the host even when only internal services need them.

## Capacity Estimate

These values are engineering estimates and must be validated using the actual production images and datasets before deployment.

### Runtime memory

Approximate steady/peak memory budget:

| Component | Expected steady range | Notes |
| --- | ---: | --- |
| Platform / Spring Boot | 350-700 MB | cap JVM explicitly |
| Analyzer / pandas + PyArrow | 300 MB idle, 1-2+ GB during analytical jobs | primary peak-memory consumer |
| Ingestor | 150-350 MB | depends on fetch/batch size |
| Kafka / KRaft | 600 MB-1 GB | configure JVM heap |
| PostgreSQL | 200-500 MB | small operational DB |
| MinIO | 150-400 MB | varies with concurrency |
| Reverse proxy/Internal Tools | 50-150 MB | small |
| OS + Docker overhead | 300-600 MB | host dependent |

Target host profiles:

```text
EOD_ONLY_MINIMUM
  2 vCPU
  4 GB RAM
  2 GB swap
  50 GB disk

EOD_RECOMMENDED
  4 vCPU
  8 GB RAM
  50-100 GB disk

INTRADAY_RESEARCH
  4 vCPU
  8 GB RAM minimum
  100 GB disk or external object storage

REALTIME_TICK
  4+ vCPU
  8-16 GB RAM
  external S3-compatible object storage strongly preferred
```

A 2 GB VM is not the default target for the full stack. It may be possible only with external PostgreSQL/object storage, strict memory caps, swap, and non-concurrent analytical workloads.

### Container/image disk footprint

Expected production image/cache footprint after removing dev-only tooling:

```text
platform image                 ~200-400 MB
analyzer image                 ~500 MB-1.0 GB
-ingestor image                ~400-800 MB
Kafka image                    ~700 MB-1.2 GB
PostgreSQL image               ~150-300 MB
MinIO image                    ~100-250 MB
proxy/internal-tools           <200 MB
```

Allow approximately:

```text
3-5 GB        pulled runtime images
5-10 GB       Docker layers/log/headroom
```

Do not size the host disk only from the final image size; Docker upgrade/pull operations temporarily need both old and new layers.

### EOD data-lake estimate

For approximately 1,600 symbols and about 10 years of daily history:

```text
~4 million EOD rows
```

Compressed Parquet OHLCV alone is expected to remain below roughly 1 GB. Indicators, symbol features, sector features, signals, research outputs, manifests, object overhead, and safety margin matter more than raw EOD candles.

Practical V1 capacity allocation:

```text
current/near-term EOD analytical lake      3-8 GB
PostgreSQL + Kafka state                   1-3 GB
Docker/runtime/log headroom                10+ GB
```

Therefore:

```text
30 GB  absolute development minimum
50 GB  recommended EOD deployment volume
```

### Intraday 1-minute estimate

Assumption:

```text
1,600 symbols
252 trading days/year
~270 possible 1-minute bars/session
60-120 compressed bytes/bar
```

Theoretical full-market 1m storage:

```text
~6.5-13 GB/year
```

Not every symbol trades every minute, so observed storage may be lower. Persisted 5m/15m/features add additional storage; budget roughly:

```text
8-18 GB/year for full-market intraday bars/features
```

For a smaller ~300-symbol research universe, the same estimate scales to roughly 1.2-2.5 GB/year for the 1m base bars before derived datasets.

### Per-tick estimate

Do not estimate tick storage from symbol count alone. Measure actual normalized ticks/day and average compressed bytes/tick.

Use:

```text
annual_bytes = ticks_per_day
             * trading_days_per_year
             * compressed_bytes_per_tick
```

Example planning ranges at 252 sessions/year:

```text
1M ticks/day, 50-80 B/tick    ~12.6-20.2 GB/year
5M ticks/day, 50-80 B/tick    ~63-101 GB/year
10M ticks/day, 50-80 B/tick   ~126-202 GB/year
```

This is the main reason realtime tick archives should target external S3-compatible storage rather than long-term EC2 root/EBS disk.

## Deployment Profiles

### Profile A — Portable EOD Single Node

Recommended first deployment.

```text
VM
├─ reverse-proxy / internal-tools
├─ platform
├─ analyzer
├─ ingestor
├─ kafka
├─ postgres
└─ minio
```

Target:

```text
4 GB RAM minimum
50 GB persistent disk
```

Use when:

- EOD/sector analysis is the primary workload;
- single-user/internal access;
- analytical jobs run sequentially;
- portability is more important than HA.

### Profile B — Portable Compute + External Storage

```text
VM
├─ platform
├─ analyzer
├─ ingestor
└─ kafka

External
├─ PostgreSQL optional
└─ S3-compatible object storage
```

This reduces local disk and MinIO/Postgres memory use.

Useful object-storage candidates:

- AWS S3;
- Cloudflare R2;
- another MinIO/S3-compatible service.

This should use the same storage interfaces/config already owned by `py_common`.

### Profile C — Intraday / Realtime

Keep the application portable through Compose, but move the data lake off the VM.

```text
compute VM
  -> S3-compatible object storage
```

Do not grow EBS/local VPS disk indefinitely for tick archives.

## Production Compose Layout

Add:

```text
docker-compose.prod.yaml
```

Production Compose should reference registry images:

```yaml
services:
  platform:
    image: ghcr.io/tanlocit9/omni-platform:${OMNI_VERSION}

  analyzer:
    image: ghcr.io/tanlocit9/omni-analyzer:${OMNI_VERSION}

  ingestor:
    image: ghcr.io/tanlocit9/omni-ingestor:${OMNI_VERSION}
```

Do not use `build:` in the primary production Compose file.

Keep local build/development Compose separate.

## Registry Strategy

Prefer GitHub Container Registry because source and CI already live on GitHub.

Publish:

```text
ghcr.io/tanlocit9/omni-platform:<git-sha>
ghcr.io/tanlocit9/omni-analyzer:<git-sha>
ghcr.io/tanlocit9/omni-ingestor:<git-sha>
ghcr.io/tanlocit9/omni-internal-tools:<git-sha>   # when available
```

Also optionally publish a moving development tag:

```text
main
```

Deployments should prefer immutable SHA/version tags.

## Multi-Architecture Images

Build:

```text
linux/amd64
linux/arm64
```

This preserves portability to:

- normal x86 VPS/EC2;
- AWS Graviton instances;
- ARM home servers;
- ARM cloud providers.

Use Docker Buildx in CI.

If a Python dependency lacks an ARM wheel, fail the ARM build explicitly rather than silently shipping architecture-specific behavior.

## Dockerfile Changes

### Platform

Keep the multi-stage Java build, but:

- pin base-image versions/digests where practical;
- use production Spring profile by default in production Compose rather than Dockerfile `CMD`;
- configure JVM container limits via `JAVA_TOOL_OPTIONS`;
- add an application health endpoint/healthcheck;
- keep runtime image non-root.

Suggested memory controls:

```text
-Xms128m
-Xmx512m
```

Tune after measurement.

### Analyzer

Current analyzer image is development-oriented.

Change production image to:

- install runtime dependencies only;
- avoid pytest/debugpy/ruff in the final runtime layer where possible;
- include `py_common` correctly as a built/installable workspace package;
- remove `--reload`;
- run a single controlled worker/server process;
- use a non-root user;
- clean build/cache artifacts.

### Ingestor

Apply the same runtime/dev dependency separation as analyzer.

Use the shared Python package as an installed artifact, not a host bind mount.

## Python Dependency Packaging

The Python runtime currently pulls heavy analytical dependencies through `py_common`, including pandas and PyArrow.

Avoid giving every Python service every analytical dependency long-term.

Direction:

```text
py-common-core
  config
  kafka
  object-storage contracts
  common message/runtime helpers

analyzer dependencies
  pandas
  pyarrow
  indicators/research libraries

-ingestor dependencies
  only ingestion/runtime packages actually needed
```

Do not split the library only for image-size aesthetics if the code boundary is not yet clean, but track this as the main image-footprint optimization.

## Persistent State Layout

For portable single-node deployment, prefer explicit host directories over opaque Docker-managed volume names:

```text
/srv/omni/
├─ postgres/
├─ kafka/
├─ minio/
├─ backups/
└─ config/
```

Compose bind mounts:

```text
/srv/omni/postgres -> PostgreSQL data
/srv/omni/kafka    -> Kafka data
/srv/omni/minio    -> MinIO data
```

This makes migration/inspection easier.

Containers remain disposable; `/srv/omni` is the machine-local state bundle.

## Kafka Persistence

Replace `/tmp` Kafka log storage with a persistent path.

Example target:

```text
/var/lib/kafka/data
```

Map it to:

```text
/srv/omni/kafka
```

Set a bounded retention policy because Kafka is not Omni's analytical archive.

Kafka should retain enough data for:

- transient worker downtime;
- replay/retry;
- deployment restarts.

Long-term data belongs in Parquet/object storage.

## Network Exposure

Production default:

```text
PUBLIC
  80/443 only

PRIVATE DOCKER NETWORK
  8080 platform
  5432 postgres
  29092 kafka
  9000 minio
```

Do not expose publicly by default:

```text
5432
9092
9000
9001
5050
```

Use SSH tunnel/VPN/admin profile when direct admin access is needed.

## Compose Profiles

Recommended optional profiles:

```text
admin
  pgadmin
  minio-console exposure if required

dev
  source bind mounts
  reload/debug behavior

local-storage
  minio

cloud-storage
  no local minio; use S3-compatible endpoint
```

Do not maintain entirely separate service definitions when Compose profiles/overrides can express the difference cleanly.

## Secrets

Production environment:

```text
.env.prod                 # local only, gitignored
.env.prod.example         # committed placeholders
```

Never retain production defaults such as:

```text
postgres/postgres
minioadmin/minioadmin
```

Required secrets should fail fast when absent.

Later cloud deployments may map secrets to AWS SSM/Secrets Manager, but V1 portability should not depend on AWS-specific secret services.

## Health Checks

Add health checks for:

```text
platform
analyzer/worker readiness where meaningful
ingestor worker readiness/heartbeat
postgres
kafka
minio
```

Do not rely solely on `container started` as readiness.

For pure consumers without HTTP endpoints, expose an internal health/heartbeat mechanism only if it provides operational value; avoid adding web servers solely for Docker health checks when a process-level check is sufficient.

## Resource Controls

Define explicit service limits for single-node deployments.

Initial 4 GB profile budget example:

```text
platform     600 MB
analyzer     1.3 GB
-ingestor    350 MB
kafka        800 MB
postgres     350 MB
minio        300 MB
proxy/UI     100 MB
```

This is deliberately tight. Add 2 GB swap and prevent multiple memory-heavy analyzer jobs from running concurrently.

For an 8 GB host, raise analyzer headroom rather than immediately increasing every service limit.

## Logging

Configure Docker log rotation:

```text
max-size: 10m
max-file: 3-5
```

Do not let container stdout logs consume the deployment volume indefinitely.

## Backup and Migration

### PostgreSQL

Backup with `pg_dump`/`pg_dumpall` as appropriate.

Keep logical database backups under:

```text
/srv/omni/backups/postgres/
```

### MinIO

MinIO/object storage is the analytical source of truth.

Migration options:

- `mc mirror` to another MinIO/S3-compatible target;
- object-storage replication later;
- stopped-volume copy only for simple single-node moves.

### Kafka

Persist Kafka across restart, but do not make full Kafka-volume backup a mandatory long-term backup dependency.

For machine migration:

1. stop producing new jobs;
2. allow consumers to drain;
3. stop Compose;
4. migrate PostgreSQL + object storage;
5. copy Kafka state only when preserving pending stream state is required;
6. start target stack;
7. validate manifests/job status.

## Pull-and-Run Target Experience

New host bootstrap:

```bash
sudo mkdir -p /srv/omni/{postgres,kafka,minio,backups,config}

docker login ghcr.io

git clone <omni-repo>
cd omni
cp .env.prod.example .env.prod
# fill secrets/config

OMNI_VERSION=<sha-or-release> \
  docker compose \
  --env-file .env.prod \
  -f docker-compose.prod.yaml \
  pull

OMNI_VERSION=<sha-or-release> \
  docker compose \
  --env-file .env.prod \
  -f docker-compose.prod.yaml \
  up -d
```

Target migration should require no language SDK, Nx, Gradle, Python, or Node installation on the host.

Only Docker Engine/Compose and the deployment configuration are required.

## CI/CD Packaging

Add GitHub Actions workflow:

```text
push/tag
   |
   v
build/test through Nx
   |
   v
Docker Buildx
   |
   +-- omni-platform
   +-- omni-analyzer
   +-- omni-ingestor
   +-- internal-tools later
   |
   v
GHCR immutable images
```

Build only affected application images when practical, but release tags should produce a coherent version set.

Recommended release metadata:

```text
Git SHA
semantic/release version
build timestamp
source repository URL
```

as OCI labels.

## Hosting Options — 2026-08

### AWS

AWS changed Free Tier for accounts created on/after 2025-07-15 to a credit-based model with up to USD 200 and a free-account period of up to six months.

Legacy AWS accounts created before 2025-07-15 retain the old model where EC2 free-tier compute lasts only the first 12 months of the account.

Therefore do not treat an EC2 instance merely being marked `Free tier eligible` as proof that the current account can run it free indefinitely.

For a paid single-node deployment, a 4 GB burstable VM is the practical starting point. Example x86 target:

```text
t3.medium
2 vCPU
4 GB RAM
```

At the published us-east-1 on-demand rate of USD 0.0416/hour, compute is roughly USD 30/month before EBS, public IPv4, transfer, tax, etc.

50 GB gp3 is roughly USD 4/month at USD 0.08/GB-month in us-east-1-style pricing.

Prefer ARM/Graviton when the multi-architecture images and dependencies are verified, because it broadens lower-cost placement options.

### Google Cloud Free Tier

Google Cloud currently includes one `e2-micro` VM and up to 30 GB standard persistent disk in its free Compute Engine allowance for eligible users.

An `e2-micro` is a shared-core machine with about 1 GB memory in the normal free-tier shape, so it is **not sufficient for the full Omni stack**.

Possible uses:

- Internal Tools/static frontend;
- small gateway/API;
- deployment experiment;
- not full Kafka + Spring + pandas + PostgreSQL + MinIO.

### Koyeb Free

Koyeb currently offers a single free instance with approximately:

```text
512 MB RAM
0.1 vCPU
2 GB SSD
```

It scales to zero and cannot attach persistent volumes or run Worker Services in the free instance class.

Use it only for a lightweight web/UI component, not the full Omni runtime.

### Cloudflare R2

R2 is relevant as an external S3-compatible data-lake option rather than as a compute host.

Current free allowance includes roughly:

```text
10 GB-month Standard storage
1M Class A operations/month
10M Class B operations/month
free Internet egress
```

This is attractive for the EOD data lake and early Internal Tools direct Parquet reads.

Before migration, verify MinIO/S3 client compatibility, presigned URL behavior, CORS, and any API differences used by Omni.

### Truly free full-stack conclusion

Outside Oracle Always Free, there is currently no mainstream always-free VM option in the reviewed set that provides the 4+ GB RAM needed to run Omni's complete single-node stack comfortably.

Best low-cost strategies are therefore:

1. use available AWS/new-cloud credits for temporary hosting;
2. self-host the portable Compose bundle on an existing machine/mini-PC;
3. use a low-cost 4 GB VPS;
4. externalize Parquet storage to a free/cheap S3-compatible service;
5. run scheduled development/research environments only when needed rather than 24/7.

## Recommended Deployment Direction

For Omni now:

```text
Phase A
  productionize Docker/Compose
  build/push GHCR images
  4 GB / 50 GB single-node EOD profile

Phase B
  move Parquet lake to configurable S3-compatible external storage when useful

Phase C
  intraday -> 8 GB host or external data lake

Phase D
  realtime tick -> external object storage + dedicated compute sizing from measured throughput
```

Do not introduce Kubernetes/ECS as a requirement yet. Docker Compose is the correct portability layer for the current single-node/internal-research stage.

## Dataset Outputs

No analytical dataset output.

Existing data remains under the canonical MinIO/S3-compatible `stock-data` bucket.

## Metadata Outputs

No new market-data metadata schema is required.

Deployment may expose operational build metadata such as:

```text
image tag
Git SHA
build timestamp
architecture
service version
```

These are deployment diagnostics, not analytical dataset manifests.

## Algorithm Feature Outputs

No direct algorithm feature output.

This phase improves reproducibility of the runtime that produces existing/future features.

## Algorithms Unlocked

No new algorithm is introduced directly.

The portable runtime enables repeatable execution of:

- existing indicators/signals;
- Sector Wave/Sector Transition;
- future Intraday EOD jobs;
- future realtime processing on larger hosts.

## Implementation Steps

### Step 1 — Measure current baseline

- [ ] Build current images locally.
- [ ] Record compressed/uncompressed image sizes.
- [ ] Run the full stack with representative EOD jobs.
- [ ] Record idle and peak `docker stats` memory/CPU.
- [ ] Record PostgreSQL/Kafka/MinIO volume sizes.
- [ ] Record `stock-data` bucket total bytes/object count from MinIO metadata/tools.

### Step 2 — Production Dockerfiles

- [ ] Separate runtime vs development dependencies.
- [ ] Remove analyzer `--reload` in production.
- [ ] Install `py_common` as part of Python image build.
- [ ] Add non-root users to Python runtime images.
- [ ] Add JVM/Python resource configuration.
- [ ] Pin important base images.

### Step 3 — Production Compose

- [ ] Add `docker-compose.prod.yaml` using registry images.
- [ ] Remove source bind mounts.
- [ ] Add Kafka persistent volume.
- [ ] Add explicit `/srv/omni` state mounts.
- [ ] Add health checks.
- [ ] Add log rotation.
- [ ] Add resource limits.
- [ ] Move pgAdmin to `admin` profile.
- [ ] Stop exposing infra ports publicly by default.

### Step 4 — Registry/CI

- [ ] Add GHCR publishing workflow.
- [ ] Build `amd64` + `arm64`.
- [ ] Tag images with Git SHA/release version.
- [ ] Add OCI labels.
- [ ] Require relevant Nx tests/build before publish.

### Step 5 — Backup/migration tooling

- [ ] Add PostgreSQL backup script.
- [ ] Add MinIO/S3 mirror script/documentation.
- [ ] Document Kafka drain/restart behavior.
- [ ] Add restore verification checklist.

### Step 6 — Cloud deployment profile

- [ ] Create `.env.prod.example`.
- [ ] Document AWS EC2 4 GB reference deployment.
- [ ] Verify ARM64/Graviton image compatibility.
- [ ] Add optional external S3-compatible storage profile.
- [ ] Add firewall/security-group requirements.

### Step 7 — Capacity validation

- [ ] Validate EOD workload on 4 GB host.
- [ ] Record swap usage and analyzer peak memory.
- [ ] If OOM/constant swap occurs, set 8 GB as minimum rather than forcing 4 GB.
- [ ] Re-estimate disk after Intraday EOD representative data is available.
- [ ] Re-estimate realtime storage from measured ticks/day and Parquet compression.

## Acceptance Criteria

- A clean host can deploy Omni using Docker/Compose without installing project language toolchains.
- Production deployment pulls immutable application images from GHCR.
- No production application source bind mounts are required.
- Analyzer does not run with hot reload.
- Kafka state persists across container restarts.
- PostgreSQL and MinIO state survive image/container replacement.
- pgAdmin is optional and disabled by default.
- Infrastructure ports are not publicly exposed by default.
- Production secrets have no insecure default values.
- EOD profile has a measured RAM/CPU/disk baseline.
- `amd64` and `arm64` images are produced or any ARM blockers are explicitly documented.
- Migration/restore of PostgreSQL and MinIO is documented and tested.
- Deployment remains cloud-neutral; AWS-specific services are optional adapters rather than runtime requirements.
