# Omni Roadmap Diagram

This diagram is a navigational view of roadmap phases and selected cross-phase dependencies. Canonical story status, dependencies, ownership, and execution eligibility remain in the [increment registry](implementation-increments.md). The [roadmap index](README.md) owns milestone and epic navigation.

Color describes evidence state, not urgency.

```mermaid
flowchart TD
    P0["P0 Correctness ✅"] --> P1["P1 Core safety — P1-I3 verification"]
    P1 --> P2["P2 Contracts — foundation complete"]
    P1 --> P3["P3 Data contracts — date normalization complete"]
    P3 --> P4["P4 Dependency guard — foundation complete; P4-I3 superseded"]
    P4 --> P14["P14 Static Graph and DispatchPlanner — P14-I1 ready"]
    P4 --> P5["P5 Deployment — superseded/deferred"]
    P5 --> P6["P6 Console expansion — superseded/deferred"]
    P4 --> P7["P7 Job operations ✅"]
    P1 --> P8["P8 Notifications — verification/in progress"]
    P2 --> P9["P9 Intraday EOD — verification/in progress"]
    P3 --> P9
    P9 --> P10["P10 Realtime — deferred; evidence retained"]
    P4 --> P11["P11 Observability — deferred debt"]
    P8 --> P11
    P4 --> P13["P13 Operator trust — pending"]
    P7 --> P13
    P14 -->|"P14-I2 graph views only"| P13
    P11 --> P12["P12 Worker throughput — deferred debt"]
    P13 -->|"P13-I1 baseline gate"| P12

    classDef done fill:#d5f5e3,stroke:#198754,color:#111;
    classDef active fill:#fff3cd,stroke:#b58105,color:#111;
    classDef pending fill:#e2e3e5,stroke:#6c757d,color:#111;
    classDef blocked fill:#f8d7da,stroke:#b02a37,color:#111;
    class P0,P4,P7 done;
    class P1,P3,P8,P9,P14 active;
    class P2,P5,P6,P11,P12,P13 pending;
    class P10 blocked;
```

## Reading the Diagram

- P14 is the active [Static Graph & DispatchPlanner epic](../030-static-graph-dispatch-planner.md).
- The P14 → P13 edge applies only to graph-specific Job Operations presentation after P14-I2; basic P13 operations remain independently deliverable.
- P10, P11, and P12 retain historical status/evidence but remain owner-gated deferred work.
- Diagram edges summarize planning relationships and never replace exact `depends_on` metadata in the increment registry.
