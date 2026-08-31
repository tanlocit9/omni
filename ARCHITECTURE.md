# Omni Architecture

This file is kept as a compatibility index for existing links. Canonical architecture and flow documentation now lives under [`docs`](docs).

## Start Here

- [Documentation map](docs/README.md)
- [System overview](docs/architecture/001-system-overview.md)
- [Where to change](docs/development/001-where-to-change.md)

## Architecture Documents

| Topic                                          | Canonical document                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------ |
| System boundaries and service responsibilities | [docs/architecture/001-system-overview.md](docs/architecture/001-system-overview.md) |
| Job execution and parent/child aggregation     | [docs/flows/001-job-execution.md](docs/flows/001-job-execution.md)                   |
| Stock/symbol sync                              | [docs/flows/002-stock-sync.md](docs/flows/002-stock-sync.md)                         |
| Indicator and signal flow                      | [docs/flows/003-indicator-signal.md](docs/flows/003-indicator-signal.md)             |
| Sector Wave flow                               | [docs/flows/004-sector-wave.md](docs/flows/004-sector-wave.md)                       |
| Kafka topics and message contracts             | [docs/data/001-kafka-contracts.md](docs/data/001-kafka-contracts.md)                 |
| Data lake datasets and paths                   | [docs/data/002-data-lake.md](docs/data/002-data-lake.md)                             |
| Database domains                               | [docs/data/003-database.md](docs/data/003-database.md)                               |
| Architecture decisions                         | [docs/adr/README.md](docs/adr/README.md)                                             |

## Service Documents

| Service         | README                                               |
| --------------- | ---------------------------------------------------- |
| Platform / Core | [apps/core/README.md](apps/core/README.md)           |
| Analyzer        | [apps/analyzer/README.md](apps/analyzer/README.md)   |
| Ingestor        | [apps/ingestor/README.md](apps/ingestor/README.md)   |
| py-common       | [libs/py-common/README.md](libs/py-common/README.md) |

## Source-of-truth Configuration

| Contract                  | Source                                                       |
| ------------------------- | ------------------------------------------------------------ |
| Kafka topics              | [configs/shared/topics.yaml](configs/shared/topics.yaml)     |
| S3 path patterns          | [configs/shared/s3-paths.yaml](configs/shared/s3-paths.yaml) |
| Platform project targets  | [apps/core/project.json](apps/core/project.json)             |
| Analyzer project targets  | [apps/analyzer/project.json](apps/analyzer/project.json)     |
| Ingestor project targets  | [apps/ingestor/project.json](apps/ingestor/project.json)     |
| py-common project targets | [libs/py-common/project.json](libs/py-common/project.json)   |
