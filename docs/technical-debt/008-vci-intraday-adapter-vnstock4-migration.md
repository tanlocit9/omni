# VCI Intraday Adapter vnstock 4.x Migration

## Status

Original public-facade failure avoided in source; provider compatibility and complete-day
coverage remain evidence-dependent.

## Context

The Ingestor [`VCIIntradayQuoteAdapter`](../../apps/ingestor/app/stocks/clients/vci_intraday.py)
previously failed when the vnstock public `Quote.intraday()` facade received an
unsupported `page` argument during P9-I1 intraday EOD processing.

## Original Problem

Observed error:

```text
TypeError: Quote.intraday() got an unexpected keyword argument 'page'
```

The earlier adapter assumed public pagination/cursor behavior that vnstock 4.x no
longer exposed through the same facade.

## Current Implementation

Current source does not implement the previously documented single public-facade call
with `start` and `end`. Instead it accesses the quote object's private provider and
calls provider-level `intraday(page_size=30_000)`.

Source tests verify that the unsupported public `page` argument is not used and that
the provider-level page-size call is made. This avoids the original exception in the
covered shape, but it creates a different compatibility boundary:

- `_provider` is a private vnstock implementation detail;
- the 30,000-row cap may truncate an unusually active session unless completeness is
  verified independently;
- provider date/session selection behavior is not explicit in the adapter call;
- source fixtures do not establish live provider completeness or production memory use.

## Current Source Assessment

- **Resolved in source:** the adapter no longer sends the unsupported public `page`
  argument that caused the recorded `TypeError`.
- **Stale documentation:** pagination parameters were not fully removed, and the current
  adapter does not call public `Quote.intraday(start=..., end=...)` as previously stated.
- **Current risk:** reliance on private `_provider` API can break across vnstock updates.
- **Current risk:** a fixed 30,000-row request needs an explicit truncation/completeness
  check.
- **Evidence-dependent:** full-session date scoping, live HOSE/HNX/UPCOM behavior,
  provider row limits, memory use, and P9-I1 end-to-end success.

## Recommended Actions

1. Prefer a supported public vnstock 4.x API when it can provide explicit session/date
   semantics and complete results; otherwise document and pin the private provider
   compatibility boundary.
2. Detect or reject possible truncation at the 30,000-row cap instead of silently
   treating a capped response as a complete trading day.
3. Add provider-contract fixtures for date scoping, empty sessions, malformed responses,
   timeout/error propagation, and cap-edge behavior.
4. Record owner-run provider evidence for representative HOSE, HNX, and UPCOM symbols
   before claiming complete-day coverage.
5. Monitor response rows and memory for high-volume sessions; introduce chunking or a
   supported streaming path only if measured data requires it.
6. Keep P9-I1 at its canonical evidence state until approved project checks and exact-head
   CI/provider evidence are recorded.

## Contract Impact

- Kafka/service-to-service protobuf: unchanged.
- Object-storage JSON manifests: unchanged; publication still requires existing
  validation and readiness semantics.
- Storage paths/dataset ownership: unchanged.
- Public Java/Python APIs: internal adapter/provider integration may change.
- Configuration/environment: dependency pinning may change if private-provider
  compatibility requires an exact vnstock version.

## Verification Required

- Adapter unit tests for supported call arguments and response normalization.
- Provider-backed evidence for complete session coverage and date selection.
- Reconciliation against EOD data for representative sessions.
- Cap-edge and memory observations for high-volume symbols.
- Approved Ingestor Nx checks and exact-head CI.

No executable verification was run for this documentation update.

## Related Work

- [Intraday EOD Flow](../flows/005-intraday-eod.md)
- [P9 Intraday EOD Implementation Plan](../plans/013-intraday-eod.md)
- [`intraday_eod.py` handler](../../apps/ingestor/app/handlers/intraday_eod.py)
