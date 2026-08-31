# ADR-005: Centralize Shared Kafka Contracts

## Status

Accepted

## Context

Kafka messages cross Java and Python service boundaries. Topic drift or payload drift can break asynchronous flows in ways that are hard to diagnose.

## Decision

Centralize topic names in [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml) and document topic ownership in [Kafka contracts](../data/001-kafka-contracts.md). Shared Python payload abstractions belong in [`libs/py-common`](../../libs/py-common); Java records belong in the Platform scheduler messaging module.

## Consequences

- Producer and consumer changes must be made together.
- Contract changes require tests and documentation updates.
- Kafka messages should carry business/job identity, not storage object routing details.
