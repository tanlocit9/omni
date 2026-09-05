# Telegram Notification Format Modernization Implementation Plan

Status: Scheduled supporting detail for P8-I1 and P8-I2; P8-I3 is deferred technical debt
Canonical status owner: [`plans/roadmap/implementation-increments.md`](../../plans/roadmap/implementation-increments.md)
Relationship: integrated into Phase 8; P8-I1 owns shared infrastructure plus operational/generic formats, P8-I2 owns immediate/digest signal formats, and P8-I3 delivery hardening/rollout is tracked in [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../technical-debt/004-post-mvp-roadmap-work.md)

## Goal

Modernize the presentation and delivery safety of every Telegram notification while preserving the existing event, routing, deduplication, and transport boundaries.

The implementation covers all current Telegram notification sources:

- operational events, including standalone and parent job success/failure;
- immediate signal-change events;
- signal digest events;
- manually submitted notification requests;
- future notification types that use `NotificationService`.

The change must produce consistent, mobile-readable messages, valid Telegram HTML, predictable severity semantics, safe 4,096-character handling, and deterministic rendering tests. Telegram-specific markup must remain out of domain events and scheduler/analyzer producers.

## Outcome

After implementation:

- every Telegram message follows one shared visual grammar for severity, title, body, details, and footer;
- operational, immediate-signal, and signal-digest messages use specialized layouts without duplicating transport logic;
- dynamic content is escaped correctly for Telegram HTML;
- no rendered message exceeds Telegram's 4,096-character limit or ends with broken HTML/entities;
- critical operational errors can notify audibly while informational and signal traffic remains silent by default;
- metadata is selected, ordered, labeled, and formatted intentionally rather than dumped as raw implementation data;
- execution and correlation identifiers remain available for diagnostics without dominating user-facing content;
- formatting can be tested independently from HTTP delivery;
- new notification types have an explicit extension point and a safe generic fallback.

## Scope

### Included

1. A Telegram rendering abstraction and structured render result.
2. Shared HTML escaping, length budgeting, value formatting, and metadata policy.
3. Renderers for:
   - generic operational notifications;
   - job lifecycle notifications represented as operational requests;
   - immediate signal changes;
   - signal digests;
   - manually submitted and unknown/future notification requests through a generic fallback.
4. Severity-aware Telegram sound behavior.
5. Fixed HTML parse-mode semantics.
6. Unit, integration-style HTTP payload, template, listener, configuration, and regression tests.
7. Configuration/example/documentation synchronization.

### Excluded

- changing Telegram bot or chat/channel routing;
- changing notification deduplication identity/cooldown semantics except where needed to keep rendering out of deduplication keys;
- changing Kafka payloads or analyzer signal calculations;
- adding images, charts, inline keyboards, callback handlers, topics/threads, or message editing;
- introducing another notification transport;
- storing notification history in a database.

## Current State

The current path is:

```text
domain/service event
  -> notification template
  -> NotificationRequest
  -> NotificationEventListener
  -> NotificationService
  -> TelegramNotificationService
  -> Telegram Bot API sendMessage
```

`NotificationRequest` contains channel, type, severity, title, message, metadata, and a deduplication key. `TelegramNotificationService` currently creates one generic HTML message, appends all metadata, truncates the final marked-up string, and sends every notification silently.

Current risks:

- final-string truncation can split tags or HTML entities;
- source contains corrupted separator/bullet characters;
- configurable parse mode can disagree with hard-coded HTML markup;
- raw metadata creates noisy messages and unstable field ordering;
- all severities use the same visual and sound behavior;
- signal and operational content lack purpose-specific formatting;
- tests assert fragments rather than the exact user-visible contract.

## Design Principles

1. Domain events and `NotificationRequest` remain transport-neutral.
2. Templates decide notification meaning; Telegram renderers decide Telegram presentation.
3. Routing remains based on `NotificationChannel`; render selection must not choose chat IDs.
4. HTML is the only supported Telegram parse mode for this increment.
5. All dynamic values are escaped exactly once.
6. Length limits are enforced while constructing valid blocks, not by cutting final HTML arbitrarily.
7. Metadata is allowlisted and ordered per notification kind; unknown metadata is not dumped by default.
8. User-facing dates use an explicit configured timezone; machine identifiers remain available in a compact diagnostics footer.
9. Rendered text is deterministic for the same request and clock/timezone inputs.
10. A generic fallback must safely render future or manually supplied notifications.

## Target Architecture

```text
NotificationRequest
        |
        v
TelegramMessageRendererRegistry
        |
        +-- OperationalTelegramRenderer
        +-- SignalChangedTelegramRenderer
        +-- SignalDigestTelegramRenderer
        +-- GenericTelegramRenderer
        |
        v
TelegramRenderedMessage
  - html
  - disableNotification
        |
        v
TelegramNotificationService
  - resolve destination
  - deduplicate
  - call renderer registry
  - POST sendMessage
  - isolate delivery failures
```

### Rendering Boundary

Introduce a transport-local interface under the notifications module, for example:

```java
public interface TelegramMessageRenderer {
    boolean supports(NotificationRequest request);
    TelegramRenderedMessage render(NotificationRequest request, long suppressedCount);
}
```

Use an explicit selector/registry with deterministic precedence. Avoid inferring specialized signal variants from title text. Prefer stable request attributes or structured metadata discriminators.

A render result should contain at least:

```java
public record TelegramRenderedMessage(
        String html,
        boolean disableNotification) {
}
```

If sound policy remains separate, return only HTML from the renderer and resolve `disable_notification` through a dedicated policy. Do not place chat IDs or bot tokens in the render result.

### Request Classification

Use the existing channel/type plus one stable, transport-neutral notification-kind discriminator when type alone is insufficient.

Recommended kinds:

```text
OPERATIONAL_GENERIC
JOB_SUCCEEDED
JOB_FAILED
JOB_DIGEST_SUCCEEDED
JOB_DIGEST_FAILED
SIGNAL_CHANGED
SIGNAL_DIGEST
MANUAL_GENERIC
```

Owner decision for P8-I2: this internal request boundary uses a hard cutover with no backward compatibility. `NotificationRequest` callers must provide an explicit `NotificationKind`; `SIGNAL_CHANGED` and `SIGNAL_DIGEST` additionally require their matching structured content and reject missing or mismatched content. `MANUAL_GENERIC` remains the explicit canonical kind for manual signal API requests. Do not derive signal kinds from type/severity, route malformed canonical signal kinds to generic rendering, or classify by matching title/message strings.

## Shared Telegram Visual Grammar

All messages follow this block order:

```text
<header>

<primary body, optional>

<ordered details, optional>

<footer, optional>
```

### Header

Use one severity/state marker and a concise bold title:

```text
ℹ️ <b>Job completed</b>
⚠️ <b>Pipeline warning</b>
🚨 <b>Job failed</b>
🟢 <b>BUY · HOSE-FPT</b>
🔴 <b>SELL · HOSE-FPT</b>
⚪ <b>HOLD · HOSE-FPT</b>
```

Use actual UTF-8 emoji only in the dedicated renderer constants and tests. Use ASCII `-` for structural bullets/separators to avoid source-encoding ambiguity.

### Operational Layout

```text
🚨 <b>Job failed</b>
Sync market signals - daily BANKS

Analyzer request timed out

<b>Job:</b> SYNC_SIGNALS
<b>Source:</b> SCHEDULER
<b>Records:</b> 12,450 synced · 20 skipped
<b>Execution:</b> 7dd9f8c2
```

Rules:

- put the outcome first;
- show the human job title before IDs;
- show error text for failures, with a bounded length;
- format counts with grouping;
- shorten UUIDs only for display, retaining full values in logs/request metadata;
- omit empty details;
- order fields explicitly rather than iterating the metadata map.

### Immediate Signal Layout

```text
🟢 <b>BUY · HOSE-FPT</b>
Trend Momentum · 1D

<b>Price:</b> 126,500 VND
<b>Signal:</b> BASELINE → BUY
<b>Score:</b> 0.84
<b>Date:</b> 31 Aug 2026
<b>Reasons:</b> TREND_UP, MOMENTUM_STRONG

<i>Updated 17:22 ICT</i>
```

Rules:

- marker follows normalized signal (`BUY`, `SELL`, `HOLD`, unknown fallback);
- symbol and transition are the primary information;
- price formatting must not assume currency unless currency/market policy is explicit; until then, use grouped numeric output without inventing a currency;
- score uses bounded precision without changing its meaning;
- reasons are human-readable and capped by count/length;
- strategy/timeframe are normalized for display but retain original semantic values;
- execution IDs are omitted from the normal body unless diagnostics mode is later introduced.

### Signal Digest Layout

```text
📊 <b>5 signal changes · Trend Momentum · 1D</b>

🟢 HOSE-FPT  BASELINE → BUY  @ 126,500
🔴 HOSE-XYZ  HOLD → SELL      @ 42,100
⚪ HNX-ABC   BUY → HOLD       @ 18,700

<i>Showing 3 of 5 · Updated 17:22 ICT</i>
```

Rules:

- preserve the original changed count even when entries are omitted;
- add entries only while the message budget allows;
- do not split an entry block;
- report omitted count accurately;
- keep each entry compact and independently escaped;
- prefer budget-based inclusion over a fixed 20-item limit, while retaining an absolute safety cap.

### Generic/Manual Fallback Layout

```text
⚠️ <b>Provided title</b>

Provided message

<b>Details</b>
- key: value
```

Rules:

- support all current manually submitted requests and future unknown kinds;
- allow only safe scalar metadata in the visible details section;
- cap number of fields and value lengths;
- sort unknown keys deterministically;
- omit sensitive/internal keys using a denylist in addition to the generic cap;
- never expose bot tokens, credentials, authorization data, raw stack traces, or full payloads.

## HTML Safety and Length Budgeting

### Parse Mode

Set `parse_mode` to `HTML` in code and configuration. Remove or deprecate externally configurable parse mode because renderers emit HTML deliberately.

Migration behavior:

- retain the old property for one compatibility release only if necessary;
- log a startup warning when a non-HTML value is configured;
- always send `HTML` after cutover;
- remove stale environment examples and documentation.

### Escaping

Create one package-private/shared Telegram HTML utility that:

- escapes `&`, `<`, and `>` in dynamic text;
- handles null/blank values through caller-defined fallbacks;
- does not re-escape renderer-owned tags;
- prevents dynamic content from introducing links or unsupported tags;
- includes tests for ampersands, angle brackets, quotes, Unicode, line breaks, and existing entity-like text.

### Length Budget

Telegram's limit applies to message text after entity parsing. Implement conservatively and test against the representation sent to the API.

Use block-aware composition:

1. reserve space for mandatory header and an omission footer;
2. escape and bound each dynamic field before wrapping it in markup;
3. append optional complete blocks only when they fit;
4. truncate long plain-text values at a Unicode code-point boundary and append `...`;
5. never substring the final marked-up HTML;
6. validate the final payload is within the selected conservative maximum;
7. if a mandatory block alone is oversized, rebuild it using bounded values rather than cutting tags.

Introduce a `TelegramMessageBuilder` or equivalent with methods such as:

```text
appendRequiredBlock(...)
tryAppendBlock(...)
remainingBudget()
build()
```

Do not split surrogate pairs, HTML entities, or renderer-owned tags.

## Metadata Presentation Policy

Define stable display labels and order separately from raw metadata keys.

Operational allowlist, in order:

```text
jobType -> Job
source -> Source
recordsSynced + recordsSkipped -> Records
failed + total -> Tasks
executionId -> Execution
parentExecutionId -> Parent
```

Signal allowlist, in order:

```text
strategy -> strategy subtitle
 timeFrame/timeframe -> timeframe subtitle
price -> Price
previousSignal + newSignal -> Signal
score -> Score
signalDate -> Date
reasonCodes -> Reasons
generatedAt/createdAt -> Updated
```

Internal-only examples:

```text
notificationKind
deliveryIdentity
deduplicationKey
manual
full execution UUIDs when a shortened display value is used
raw analyzer metadata not explicitly selected
```

Generic fallback denylist should include keys matching credential/token/secret/password/authorization/cookie patterns, case-insensitively.

## Value Formatting

Introduce small deterministic formatters rather than locale-dependent `toString()` output:

- numbers: grouped integral values and bounded decimal precision;
- scores: default two decimals, preserving `n/a` for missing/invalid values;
- dates: `dd MMM uuuu` using `Locale.ENGLISH`;
- times: `HH:mm z` in configured display timezone;
- signal names: uppercase normalized display with safe unknown fallback;
- reason codes: comma-separated, capped, with `_` optionally converted to spaces only if product wording approves it;
- UUIDs: first eight characters for display only;
- collections/maps: specialized renderers only; generic fallback renders a bounded summarized value.

Add a configuration property for display timezone, defaulting to `Asia/Bangkok`, for example:

```yaml
app:
  notifications:
    telegram:
      display-time-zone: ${TELEGRAM_DISPLAY_TIME_ZONE:Asia/Bangkok}
```

Invalid timezone configuration should fail fast during property binding/bean creation with a clear message rather than silently changing timestamps.

## Sound Policy

Resolve `disable_notification` using channel, kind, and severity:

```text
OPERATIONS + ERROR   -> false
OPERATIONS + WARNING -> configurable; default true
OPERATIONS + INFO    -> true
SIGNALS + any        -> true
```

Expose only a small configuration surface if operators need overrides. Recommended V1 is fixed policy with one property such as `audible-operational-errors=true`. Do not allow individual producers to control Telegram sound directly.

## Delivery Service Refactor

Keep `TelegramNotificationService` responsible for:

1. resolving destination from `NotificationChannel`;
2. checking global/channel configuration;
3. deduplication admission;
4. invoking the renderer registry;
5. constructing the Bot API payload;
6. making the HTTP request;
7. structured logging and exception isolation.

Move these responsibilities out of the service:

- HTML layout;
- raw metadata iteration;
- arbitrary final-string truncation;
- severity marker selection;
- signal/job value formatting;
- sound policy if represented by a dedicated component.

Delivery logs should include channel, kind, type, severity, title, rendered length, suppression count, and success/failure. Do not log full rendered messages, sensitive metadata, bot tokens, or token-bearing URLs.

## Template and Producer Changes

### Operational Templates

Update `OperationalNotificationTemplate` and `JobNotificationTemplate` to supply stable notification kinds and structured metadata. Preserve channel-neutral wording and do not add HTML.

Ensure job success/failure and parent digest variants are distinguishable without parsing titles.

### Signal Templates

Update `SignalChangedNotificationTemplate` and `SignalNotificationTemplate` to supply complete structured values needed by Telegram renderers. Avoid pre-rendering dense prose that must then be parsed by the transport.

Preferred direction:

- keep a plain-text `message` suitable for non-Telegram transports/fallbacks;
- carry signal items as typed/structured request content if the request model is extended;
- otherwise use stable metadata records/maps as an interim boundary;
- do not parse existing message strings inside Telegram renderers.

### Manual Notifications

Manual requests continue through the generic renderer unless the API explicitly accepts a known notification kind with a validated schema. User-provided metadata is untrusted display input and must be escaped, capped, and filtered.

## Contract Impact

### Kafka/service-to-service protobuf

No Kafka or protobuf contract change is required. Analyzer signal notification payloads already provide the required values. If implementation discovers a missing semantic field, create a separate contract increment rather than deriving it from display strings.

### Object-storage JSON manifest

No object-storage manifest change.

### Storage path/dataset ownership

No storage path or dataset ownership change.

### Public Java/Python API

Java notification APIs may change internally:

- `NotificationRequest` may gain `NotificationKind` and optional structured content;
- Telegram renderer interfaces and render-result records are new internal APIs;
- notification templates must populate the new classification/content fields.

Keep compatibility constructors where practical to limit migration risk. No Python API change is planned.

### Configuration/environment contract

Expected changes:

- Telegram parse mode becomes fixed to HTML; stale non-HTML configuration is deprecated/removed;
- add `TELEGRAM_DISPLAY_TIME_ZONE`, default `Asia/Bangkok`;
- optionally add `TELEGRAM_AUDIBLE_OPERATIONAL_ERRORS`, default `true`;
- existing enabled, bot token, operations/signals destination, API URL, and deduplication settings remain unchanged.

Update `.env.example`, `.env.deploy.example`, `application.yaml`, and deployment compose files only where they expose affected properties.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output. This increment presents existing signal decisions and metadata; it does not create or alter analytical features.

## Algorithms Unlocked

No new algorithm is unlocked. The implementation makes existing algorithm outputs safer and easier to monitor and reduces the risk of losing alerts because Telegram rejects malformed messages.

## Roadmap Increment Allocation

- **P8-I1 — Operational and generic formats:** freeze shared contracts; add `NotificationKind`, HTML safety, block-aware budgeting, deterministic value/metadata formatting, timezone and sound policies; implement operational, job-lifecycle, and generic/manual renderers with golden tests.
- **P8-I2 — Signal formats:** build on P8-I1 infrastructure to implement immediate BUY/SELL/HOLD and signal-digest renderers, structured signal template data, budget-based digest inclusion, and exact signal golden tests without changing Analyzer calculations or Kafka/Proto3 contracts.
- **P8-I3 — Deferred technical debt:** delivery retries, distributed idempotency, dead-letter outcomes, observability expansion, configuration/rollout work, and non-production manual verification require a new owner decision after MVP.

Each increment must remain independently reviewable. P8-I1 must not include signal-specific renderers, P8-I2 must not add delivery retries/provider behavior, and P8-I3 must not redesign notification meaning or layouts except to repair an attributable integration defect.

## Implementation Sequence

### Phase 1 - Freeze the User-Visible Contract

1. Inventory all `NotificationRequest` construction sites and classify each current notification.
2. Define exact golden examples for operational info/warning/error, job success/failure, immediate signal, digest signal, and generic/manual messages.
3. Decide stable metadata labels, field ordering, timezone, numeric precision, reason-code presentation, and sound matrix.
4. Add characterization tests for routing, deduplication, and current producers before structural refactoring.

Exit condition: every current notification source maps to one documented target layout and sound behavior.

### Phase 2 - Add Structured Classification

1. Introduce `NotificationKind` and compatibility defaults.
2. Update operational, job, immediate-signal, digest-signal, and manual construction paths.
3. Ensure deduplication uses stable request identity and not rendered text.
4. Test classification and listener routing independently.

Exit condition: no renderer needs title/message pattern matching.

### Phase 3 - Build Rendering Infrastructure

1. Add `TelegramMessageRenderer`, registry/selector, render result, HTML utility, block-aware builder, value formatters, and sound policy.
2. Lock output to HTML.
3. Add display-timezone configuration and validation.
4. Unit-test escaping, Unicode-safe bounds, complete-block admission, exact 4,096 boundary behavior, and deterministic formatting.

Exit condition: generic rendering is safe and independently testable without HTTP.

### Phase 4 - Implement All Renderers

1. Implement operational renderer, including job lifecycle variants.
2. Implement immediate signal renderer.
3. Implement signal digest renderer with budget-based item inclusion and accurate omission count.
4. Implement generic/manual fallback with metadata filtering.
5. Add golden tests for every layout and edge-case fallback.

Exit condition: every inventoried request selects exactly one renderer, with generic fallback only where intended.

### Phase 5 - Integrate Delivery

1. Inject the renderer registry/policy into `TelegramNotificationService`.
2. Remove generic raw metadata dumping and final HTML substring truncation.
3. Send renderer-provided HTML and severity-aware `disable_notification`.
4. Preserve destination resolution, deduplication, HTTP behavior, and exception isolation.
5. Extend structured delivery logs without logging message bodies or secrets.

Exit condition: Bot API payload tests verify exact text, `HTML` parse mode, chat routing, and sound behavior.

### Phase 6 - Configuration and Documentation Cutover

1. Update application configuration and environment examples.
2. Remove/deprecate old parse-mode documentation.
3. Add target examples and extension guidance to notification documentation.
4. Reconcile this plan with `TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md` so the older plan does not claim unsafe truncation/parse-mode behavior should remain unchanged.
5. Complete manual Telegram verification in both configured channels.

Exit condition: code, examples, deployment configuration, and canonical docs describe the same contract.

## Testing Strategy

### Renderer Unit Tests

Cover:

- exact golden HTML for each notification kind;
- deterministic field order;
- null, blank, missing, malformed, and unknown values;
- escaping of `&`, `<`, `>`, entity-like input, multiline text, and Unicode;
- mojibake regression: expected separators/bullets/markers are exact;
- long title, body, error, metadata, reason list, and digest list;
- no broken tag/entity and no oversized final message;
- Unicode code points/surrogate pairs remain intact;
- numeric precision/grouping and timestamp conversion to `Asia/Bangkok`;
- generic metadata filtering and sensitive-key denial;
- display UUID shortening without modifying underlying request values;
- accurate digest and metadata omission summaries.

### TelegramNotificationService Tests

Cover:

- operations and signals chat resolution;
- disabled/missing channel behavior remains isolated;
- global disable sends nothing;
- exact payload `text`, `parse_mode`, and `disable_notification`;
- operational error is audible by default;
- info, warning-default, and signal messages remain silent;
- suppression summary is passed to and safely rendered by the renderer;
- deduplication behavior does not depend on display formatting;
- delivery failure remains isolated;
- rendered length is logged as metadata but message content is not logged.

### Template and Listener Tests

Cover:

- each template emits the expected channel, type, kind, severity, structured fields, and deduplication key;
- immediate signal and signal digest remain distinct;
- operational failures remain routed to operations even when related to signal jobs;
- transactional `AFTER_COMMIT` behavior for signal digests remains unchanged;
- manual requests select generic/manual behavior safely.

### Configuration Tests

Cover:

- default display timezone;
- valid timezone override;
- invalid timezone rejection;
- audible operational error default/override if configurable;
- HTML parse-mode cutover/deprecation behavior;
- existing channel and deduplication binding remains compatible.

### Manual Verification

Use non-production Telegram chats and send at least:

1. operational success;
2. operational warning;
3. operational failure with `<`, `&`, multiline text, and a long error;
4. immediate BUY, SELL, HOLD, and unknown signal;
5. signal digest small enough to fit;
6. oversized digest that omits entries;
7. generic/manual message with unsafe HTML and sensitive-looking metadata;
8. duplicate notification followed by post-cooldown suppression summary.

Confirm desktop and mobile readability, channel routing, audible/silent behavior, dates in ICT, no Telegram entity errors, and no exposed secrets.

## Verification

Required checks, subject to explicit user approval before execution per repository policy:

```text
Core notification unit tests
Core configuration binding tests
Core module compile/build
Core lint/format checks
Nx affected tests/build for touched projects
Manual Telegram delivery verification in operations and signals test chats
```

Candidate focused commands must be identified from existing Nx/project configuration during implementation rather than guessed in this plan.

Status: not run; this task produces the implementation plan only.

## Observability and Rollout

1. Deploy first with test chat IDs or a staging bot.
2. Monitor Telegram API failures, especially `400 Bad Request: can't parse entities` and message-length errors.
3. Compare notification counts by channel/kind before and after cutover to detect renderer-selection gaps.
4. Keep delivery failures non-fatal to scheduler/analyzer work.
5. Roll out production routing after representative manual verification.
6. Roll back by reverting renderer integration as one unit; do not partially restore configurable Markdown while retaining HTML renderers.

Recommended metrics/log fields if the existing observability stack supports them:

```text
telegram.notification.attempted{channel,kind,severity}
telegram.notification.sent{channel,kind,severity}
telegram.notification.failed{channel,kind,severity,status}
telegram.notification.suppressed{channel,kind}
telegram.notification.omitted_blocks{kind}
```

Do not use raw title, symbol, chat ID, or execution ID as unbounded metric labels.

## Risks and Mitigations

- **Request model churn:** add compatibility constructors and migrate construction sites in one phase.
- **Renderer misclassification:** require explicit `NotificationKind`; prohibit title parsing.
- **Telegram length ambiguity:** use conservative budgeting and integration payload tests; verify with real Bot API messages.
- **HTML double escaping:** centralize escaping and distinguish trusted renderer markup from untrusted values.
- **Metadata loss:** retain full metadata in request/logging context while intentionally reducing visible fields.
- **Changed alert noise:** document and test the sound matrix; make only operational errors audible by default.
- **Timezone misunderstanding:** use explicit configured `ZoneId` and display `ICT`/zone text.
- **Future notification type bypass:** generic fallback is mandatory and safe; add a test that unknown kinds cannot fail rendering.
- **Sensitive manual metadata:** combine allowlist/denylist, caps, and no body logging.

## Repository Guidance Updates

Review during implementation:

- `AGENTS.md` - likely no change unless notification extension rules become repository-wide guidance.
- `CLAUDE.md` - likely no change; no Nx/tool workflow change.
- `.roo/rules/` - update only if notification-specific coding rules already exist there.
- `docs/README.md` - add this plan if canonical implementation plans are indexed individually.
- `docs/plans/007-telegram-multi-channel.md` - update completed-state wording and replace claims that old parse-mode/truncation behavior remains unchanged.
- relevant notification flow/service documentation - document renderer ownership, classification, HTML safety, and sound policy.
- `.agents/skills/manual-verification-handoff/SKILL.md` - no change expected unless the Telegram manual-verification procedure becomes reusable skill guidance.

If review confirms no repository-wide agent instruction changed, explicitly record that `AGENTS.md`, `CLAUDE.md`, and workspace rules require no edits.

## Acceptance Criteria

1. Every current Telegram notification source is inventoried and mapped to an explicit notification kind and renderer.
2. Operational, job lifecycle, immediate signal, signal digest, and generic/manual layouts have approved golden examples.
3. Telegram output always uses HTML and all dynamic values are escaped exactly once.
4. No code arbitrarily truncates final marked-up HTML.
5. Every rendered payload remains within Telegram's message limit and contains complete tags/entities and intact Unicode.
6. No corrupted separator/bullet text remains.
7. Metadata is ordered and filtered by policy; sensitive/internal metadata is not dumped into messages.
8. Operational errors are audible by default; informational and signal notifications are silent.
9. Display times use the configured timezone, defaulting to `Asia/Bangkok`.
10. Deduplication identity, channel routing, listener transaction semantics, and delivery exception isolation remain correct.
11. Exact-payload tests cover all renderers, escaping, boundary lengths, sound policy, routing, configuration, and fallback behavior.
12. Manual verification succeeds for representative and oversized messages in operations and signals test chats.
13. Configuration examples and notification documentation match the implemented behavior.
14. Relevant repository guidance is updated or explicitly confirmed unchanged.
15. Required approved checks pass before the implementation is marked Done.

## Definition of Done

```text
all notification sources classified
+ shared safe rendering infrastructure implemented
+ all specialized and fallback renderers implemented
+ Telegram delivery integrated without routing/deduplication regressions
+ exact and boundary tests passing
+ approved build/lint/affected checks passing
+ manual Telegram verification complete
+ configuration/docs/guidance synchronized
```
