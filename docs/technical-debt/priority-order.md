# Technical-Debt Priority Diagram

This diagram summarizes conditional priority lanes from the [technical-debt index](README.md). It is not an implementation schedule and does not override the [canonical increment registry](../plans/roadmap/implementation-increments.md).

P0–P3 labels describe risk priority. Color describes evidence state. Arrows show conditional activation lanes, not mandatory implementation dependencies.

```mermaid
flowchart TD
    Gate["Check activation evidence"]
    Gate --> Safety["P0/P1: safety and correctness"]
    Gate --> Measured["P2: measured performance"]
    Gate --> Later["P3: approved expansion"]

    Safety --> Identity["TD-003: identity before exposure"]
    Safety --> Data["TD-001/005/008: status and completeness"]
    Safety --> Kafka["TD-009/010: offsets and recovery"]
    Measured --> Resources["TD-007/009: resources and concurrency"]
    Measured --> Planner["TD-012 to Plan 030: bounded graph dispatch"]
    Measured --> GuardResidue["TD-013: legacy guard residue"]
    Measured --> DispatchResidue["TD-014: dispatcher evidence transfer"]
    Later --> Features["TD-002/004/011: operations and features"]

    classDef done fill:#d5f5e3,stroke:#198754,color:#111;
    classDef active fill:#fff3cd,stroke:#b58105,color:#111;
    classDef pending fill:#e2e3e5,stroke:#6c757d,color:#111;
    classDef blocked fill:#f8d7da,stroke:#b02a37,color:#111;
    class Gate pending;
    class Safety,Identity,Data,Kafka active;
    class Planner active;
    class Measured,Resources,GuardResidue,DispatchResidue,Later,Features pending;
```

## Reading the Diagram

- TD-012 is a historical promoted-design record; active bounded work is owned by [Plan 030](../plans/030-static-graph-dispatch-planner.md) and P14-I1 through P14-I3.
- Persisted topology, expanded provider policy, adaptive quotas, and advanced fairness remain deferred.
- TD-013 keeps unused blocked-job tracking cleanup and optional missing dependency evaluators outside P14 unless an explicit activation trigger occurs.
- TD-014 preserves unclosed P4-I3 evidence while P14-I3 owns the final dependency-aware dispatcher safety proof.
- TD-009 contains independent offset-safety and measured-capacity slices; a correctness fix does not require the full capacity epic.
- Closed TD-006 is intentionally excluded.
