# Phase 8 — Multi-Channel Notification Routing

## Goal

Modernize Telegram operational and signal notifications with typed classification, safe purpose-specific rendering, recipient-aware routing, and reliable delivery without coupling job aggregation to delivery providers.

Owner-approved execution exception (2026-09-04): execute P8-I1, P8-I2, and P8-I3 ahead of P2-I2/P2-I3. Existing internal Java notification events and `NotificationRequest` remain the boundary for Phase 8; this phase must not change Kafka or Proto3 contracts. If a renderer requires a semantic field unavailable at that boundary, stop and return the contract change to Phase 2 rather than parsing display text or expanding the exception.

## Increment P8-I1 — Operational and generic Telegram notification formats

| Field                   | Value                                                 |
| ----------------------- | ----------------------------------------------------- |
| id                      | P8-I1                                                 |
| title                   | Operational and generic Telegram notification formats |
| status                  | verification_pending                                  |
| priority                | critical                                              |
| depends_on              | [P1-I4]                                               |
| blocks                  | [P8-I2, P8-I3]                                        |
| owned_modules           | [apps/core, configs, docs/plans]                      |
| execution_mode          | autonomous                                            |
| requires_owner_decision | false                                                 |
| pr                      | null                                                  |
| last_verified_commit    | null                                                  |

Goal: establish shared safe Telegram rendering and modernize operational, job-lifecycle, generic, and manual notification formats without changing signal presentation.

Scope: add transport-neutral notification classification, a renderer registry, HTML escaping, block-aware length budgeting, deterministic value and metadata formatting, the `Asia/Bangkok` display-time default, severity-aware sound policy, operational/job renderers, and a safe generic/manual fallback. Preserve existing operations/signals destination resolution, deduplication identity, and listener transaction semantics. Detailed presentation rules are in [`docs/plans/012-telegram-notification-format-modernization.md`](../../docs/plans/012-telegram-notification-format-modernization.md).

Acceptance criteria: every operational, job-lifecycle, generic, and manual request resolves to an explicit kind and renderer; dynamic content is escaped exactly once; output contains valid complete HTML within Telegram's 4,096-character limit; operational metadata is selected and ordered deliberately; sensitive generic metadata is filtered; operational errors are audible by default while informational notifications remain silent; and domain publishers contain no Telegram markup or chat IDs.

Required tests/checks: classification/template tests; exact golden operational/job/generic renderer tests; escaping, Unicode, metadata-filtering, timezone, sound-policy, and 4,096-boundary tests; routing and deduplication regressions; mocked HTTP payload tests; and Core Nx test/build/format checks.

Stop conditions: stop if implementation needs a Kafka/Proto3 field, requires title/message parsing for classification, changes deduplication identity, includes signal-specific renderers, or needs live credentials.

Verification evidence (2026-09-05): local recorder conclusion is `PASS P8-I1 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code` for `nx run platform:test`, `nx run platform:build`, and explicitly scoped Prettier formatting of P8-I1 documentation/configuration files. Focused coverage includes renderer classification, HTML safety, Unicode boundaries, metadata filtering, timezone and sound policies, routing, deduplication, listener behavior, and mocked HTTP payloads. Platform defines no lint or Java format Nx target. No live Telegram verification, PR, or CI evidence exists; status therefore remains `verification_pending` rather than completed.

## Increment P8-I2 — Immediate and digest signal notification formats

| Field                   | Value                                            |
| ----------------------- | ------------------------------------------------ |
| id                      | P8-I2                                            |
| title                   | Immediate and digest signal notification formats |
| status                  | verification_pending                             |
| priority                | critical                                         |
| depends_on              | [P8-I1]                                          |
| blocks                  | [P8-I3]                                          |
| owned_modules           | [apps/core, configs, docs/plans]                 |
| execution_mode          | autonomous                                       |
| requires_owner_decision | false                                            |
| pr                      | null                                             |
| last_verified_commit    | null                                             |

Goal: modernize immediate signal-change and signal-digest presentation using the shared rendering infrastructure established by P8-I1.

Scope: add explicit immediate-signal and signal-digest kinds, update signal templates to provide structured values available from the current internal boundary, implement BUY/SELL/HOLD/unknown layouts, deterministic price/score/date/reason formatting, budget-based digest item inclusion, and accurate omitted-item summaries. Apply the owner-approved hard cutover with no backward compatibility: remove implicit signal-kind construction and legacy signal rendering. Preserve Analyzer calculations, signal event semantics, the SIGNALS destination, digest `AFTER_COMMIT` handling, deduplication identity, and silent signal delivery.

Acceptance criteria: immediate and digest signal events select distinct renderers without parsing title/message text; canonical signal kinds require matching structured content and fail deterministically when it is missing or mismatched; malformed canonical signal kinds never route to generic rendering; explicit manual signal API requests remain `MANUAL_GENERIC`; symbol and transition are primary; strategy/timeframe and optional values are presented consistently; unknown semantic field values degrade safely; digest entries are included only as complete blocks; original and omitted counts remain accurate; all values are escaped; every valid payload remains within Telegram's limit; and failed signal-processing jobs continue to route to OPERATIONS rather than SIGNALS.

Required tests/checks: signal template/classification tests; hard-cutover rejection tests for missing/mismatched content and removed implicit construction; exact BUY, SELL, HOLD, unknown, and digest golden tests; price/score/date/reason fallback tests; oversized digest and omission-count tests; escaping and Unicode boundaries; listener channel and `AFTER_COMMIT` regression tests; exact mocked Telegram payload tests; and Core Nx test/build/format checks.

Stop conditions: stop if a required semantic value is absent from the current Java event/request boundary, if implementation would parse pre-rendered prose, alter Analyzer calculations, change Kafka/Proto3 contracts, or introduce delivery retry/provider behavior owned by P8-I3.

Verification evidence (2026-09-05): local recorder conclusion is `PASS P8-I2 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code` for `nx run platform:test`, `nx run platform:build`, and scoped Prettier checking of the three P8-I2 documentation files. Coverage includes typed signal-change/digest content, purpose-specific renderers, deterministic formatting, digest budgeting, hard-cutover rejection, templates, listeners, HTTP payloads, and deduplication regressions. Static inspection confirms no Kafka/Proto3 or Analyzer calculation changes. Status remains `verification_pending`; no commit, PR, CI, or live Telegram evidence is claimed.

## Increment P8-I3 — Telegram delivery safety, retries, idempotency, and rollout

| Field                   | Value                                                       |
| ----------------------- | ----------------------------------------------------------- |
| id                      | P8-I3                                                       |
| title                   | Telegram delivery safety, retries, idempotency, and rollout |
| status                  | pending                                                     |
| priority                | critical                                                    |
| depends_on              | [P8-I1, P8-I2]                                              |
| blocks                  | []                                                          |
| owned_modules           | [apps/core, configs, docs/flows]                            |
| execution_mode          | autonomous                                                  |
| requires_owner_decision | false                                                       |
| pr                      | null                                                        |
| last_verified_commit    | null                                                        |

Goal: harden Telegram delivery and complete a controlled rollout without changing job execution status or adding another transport.

Scope: integrate all operational and signal renderer output into delivery, preserve channel isolation and cooldown semantics, add retry/observability behavior where absent, synchronize configuration and documentation, and verify representative operational and signal messages in non-production Telegram destinations. Additional providers remain future scope.

Acceptance criteria: duplicate terminal events do not duplicate deliveries; provider failures are retryable and observable; exhausted deliveries record a deliberate failure/dead-letter outcome where supported; rendered content is not logged; Telegram API payloads use fixed HTML and correct sound behavior; operations/signals destinations remain isolated; and representative plus oversized messages pass manual desktop/mobile verification.

Required tests/checks: idempotency, retryability, mocked Telegram adapter, exact HTTP payload, channel isolation, configuration binding, affected Nx checks, and owner-authorized manual Telegram verification.

Stop conditions: stop before manual verification if non-production bot/chat credentials are unavailable; never use production credentials merely to satisfy completion evidence.
