# Telegram Multi-Channel Notification Implementation Plan

## Goal

Split Telegram notifications into separate destinations by purpose while keeping the current notification architecture simple and extensible.

V1 channels:

- `OPERATIONS` — scheduler/job execution, pipeline failures, infrastructure/data-processing diagnostics.
- `SIGNALS` — successful signal-change digests intended for market monitoring.

Use **one Telegram bot with multiple chat/channel IDs**. Do not create one Telegram service or one bot per notification category.

## Current State

The current flow already distinguishes notification event types:

```text
OperationalNotificationEvent
  -> OperationalNotificationTemplate
  -> NotificationService.send(...)

SignalDigestNotificationEvent
  -> SignalNotificationTemplate
  -> NotificationService.send(...)
```

However, `TelegramNotificationService` currently resolves a single configured `chatId`, so both event types are delivered to the same Telegram destination.

## Routing Decision

Introduce an explicit notification destination/category:

```java
public enum NotificationChannel {
    OPERATIONS,
    SIGNALS
}
```

Preferred service contract:

```java
public interface NotificationService {
    void send(NotificationChannel channel, NotificationRequest request);
}
```

The channel identifies the logical notification destination. Telegram remains only a transport implementation.

Do not put Telegram-specific chat IDs into domain events.

## Routing Rules

### OPERATIONS

Route the following to `OPERATIONS`:

- scheduled job success/failure notifications;
- parent/child job aggregation failures;
- Sector Transition diagnostic failures;
- ingestion/analyzer operational failures;
- notification delivery diagnostics where applicable;
- `SYNC_SIGNALS` job failure.

Important: a failed signal-processing job is an operational problem, not a market signal.

### SIGNALS

Route only market-facing signal output to `SIGNALS`:

- `SignalDigestNotificationEvent`;
- future signal/recommendation digest events;
- future market/sector alert events explicitly classified as signal output.

Do not send generic successful scheduler messages to the Signals channel.

## Target Flow

```text
                          +-------------------------+
                          | OperationalNotification |
                          +------------+------------+
                                       |
                                       v
                              OPERATIONS route
                                       |
                                       v
                         Telegram operations chat

JobService / Policies
       |
       +------------------+
                          |
                          v
                   SignalDigestEvent
                          |
                          v
                     SIGNALS route
                          |
                          v
                          Telegram signals chat
```

## Configuration

Keep shared Telegram transport configuration at the root and configure destinations separately.

Recommended YAML:

```yaml
app:
  notifications:
    telegram:
      enabled: ${TELEGRAM_ENABLED:false}
      bot-token: ${TELEGRAM_BOT_TOKEN:}
      parse-mode: HTML
      api-base-url: https://api.telegram.org
      channels:
        operations:
          chat-id: ${TELEGRAM_OPERATIONS_CHAT_ID:}
          enabled: true
        signals:
          chat-id: ${TELEGRAM_SIGNALS_CHAT_ID:}
          enabled: true
```

Environment variables:

```text
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
TELEGRAM_OPERATIONS_CHAT_ID=...
TELEGRAM_SIGNALS_CHAT_ID=...
```

The same bot token is used for both destinations.

## Properties Model

Refactor `TelegramNotificationProperties` from one `chatId` to a channel map or equivalent nested configuration.

Preferred shape:

```java
@ConfigurationProperties(prefix = "app.notifications.telegram")
public record TelegramNotificationProperties(
        boolean enabled,
        String botToken,
        String parseMode,
        String apiBaseUrl,
        Map<NotificationChannel, TelegramChannelProperties> channels) {
}

public record TelegramChannelProperties(
        boolean enabled,
        String chatId) {
}
```

If Spring binding to enum-map keys becomes unnecessarily awkward, use string keys internally and normalize them once during configuration binding/resolution.

Do not scatter raw configuration keys throughout listeners/services.

## Telegram Destination Resolver

Keep destination lookup out of message formatting.

Example responsibility:

```java
@Component
@RequiredArgsConstructor
public class TelegramDestinationResolver {

    private final TelegramNotificationProperties properties;

    public Optional<String> resolve(NotificationChannel channel) {
        // Resolve enabled channel and configured chat id.
    }
}
```

This provides a clean extension point for future channels such as:

```text
OPERATIONS
SIGNALS
SECURITY
DATA_QUALITY
```

without changing Telegram send logic.

For V1, the resolver may also remain a private method inside `TelegramNotificationService` if introducing a separate class would add no value yet.

## TelegramNotificationService Changes

Change:

```java
send(NotificationRequest request)
```

to:

```java
send(NotificationChannel channel, NotificationRequest request)
```

Delivery flow:

```text
request + logical channel
        |
        v
resolve channel configuration
        |
        +-- disabled/missing --> log and skip
        |
        v
format existing NotificationRequest
        |
        v
POST Telegram sendMessage using resolved chat_id
```

Keep the existing:

- HTML escaping;
- parse mode handling;
- 4096-character protection;
- delivery exception isolation;
- shared `RestClient`.

Do not duplicate these concerns into channel-specific services.

## Listener Changes

The current event split is already the correct routing boundary.

Update `NotificationEventListener` approximately as follows:

```java
@EventListener
public void onOperationalNotification(OperationalNotificationEvent event) {
    notificationService.send(
            NotificationChannel.OPERATIONS,
            operationalNotificationTemplate.render(event));
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onSignalDigestNotification(SignalDigestNotificationEvent event) {
    notificationService.send(
            NotificationChannel.SIGNALS,
            signalNotificationTemplate.render(event));
}
```

This keeps transport routing centralized without leaking chat IDs into scheduler policies or templates.

## Event and Template Boundaries

Keep these responsibilities separate:

```text
JobNotificationPolicy
    -> decides WHAT notification should exist

NotificationTemplate
    -> decides HOW the message is rendered

NotificationEventListener
    -> decides WHICH logical notification channel receives the event

TelegramNotificationService
    -> decides HOW to deliver it through Telegram
```

Do not make notification policies aware of Telegram.

Do not make templates choose Telegram chat IDs.

## Failure Semantics

A missing Signals destination must not break Operations notifications, and vice versa.

Examples:

```text
operations configured + signals missing
  -> operations notifications continue normally
  -> signal notifications log a targeted warning and are skipped

signals configured + operations missing
  -> signal notifications continue normally
  -> operational notifications are skipped with warning
```

Application startup should not fail merely because one optional Telegram destination is disabled.

Global `telegram.enabled=false` disables all Telegram delivery.

## Logging

Include logical channel in delivery logs:

```text
Sending Telegram notification channel=OPERATIONS type=... severity=...
Sending Telegram notification channel=SIGNALS type=... severity=...
```

Do not log:

- bot token;
- full Telegram API URL containing token;
- sensitive notification metadata.

`chatIdPresent=true/false` is sufficient for configuration diagnostics.

## Testing

### TelegramNotificationServiceTest

Add coverage for:

- `OPERATIONS` resolves the operations chat ID;
- `SIGNALS` resolves the signals chat ID;
- missing operations config skips only operations delivery;
- missing signals config skips only signal delivery;
- globally disabled Telegram sends nothing;
- formatting/truncation behavior remains unchanged;
- Telegram delivery exception does not escape the service.

### NotificationEventListenerTest

Verify:

```text
OperationalNotificationEvent
  -> NotificationChannel.OPERATIONS

SignalDigestNotificationEvent
  -> NotificationChannel.SIGNALS
```

Also verify signal digest still uses `AFTER_COMMIT` semantics.

### Configuration Binding Test

Verify both destinations bind correctly from application configuration/environment variables.

## Implementation Steps

### Step 1 — Introduce logical channels

- [ ] Add `NotificationChannel` with `OPERATIONS` and `SIGNALS`.
- [ ] Change `NotificationService.send(...)` to accept the channel.
- [ ] Update test doubles/mocks and compile errors.

### Step 2 — Refactor Telegram properties

- [ ] Remove the single global `chatId`.
- [ ] Add per-channel Telegram destination configuration.
- [ ] Add `TELEGRAM_OPERATIONS_CHAT_ID`.
- [ ] Add `TELEGRAM_SIGNALS_CHAT_ID`.
- [ ] Update `.env.example` and deployment examples.

### Step 3 — Route events

- [ ] Route `OperationalNotificationEvent` to `OPERATIONS`.
- [ ] Route `SignalDigestNotificationEvent` to `SIGNALS`.
- [ ] Keep signal processing/job failures as operational events.

### Step 4 — Update Telegram transport

- [ ] Resolve destination from `NotificationChannel`.
- [ ] Reuse one bot token and one `RestClient`.
- [ ] Log the logical channel on send/skip/failure.
- [ ] Keep existing message formatting and escaping behavior.

### Step 5 — Tests and documentation

- [ ] Update notification service tests.
- [ ] Update listener routing tests.
- [ ] Add configuration binding tests.
- [ ] Update `docs/flows/job-execution.md` with the two Telegram routes.

## Future Extension

Do not implement these in V1, but keep the model compatible with:

```text
NotificationChannel.SECURITY
NotificationChannel.DATA_QUALITY
NotificationChannel.SYSTEM
```

Possible later routing abstraction:

```text
NotificationEvent
   -> NotificationRouter
        -> Telegram
        -> Email
        -> Slack
        -> Web/Internal Tools
```

At that point the logical channel remains stable while transports/subscriptions become configurable.

## Acceptance Criteria

- Operations and signal notifications can use different Telegram chat/channel IDs.
- One Telegram bot token is sufficient for both destinations.
- `OperationalNotificationEvent` is delivered only to `OPERATIONS`.
- `SignalDigestNotificationEvent` is delivered only to `SIGNALS`.
- A `SYNC_SIGNALS` execution failure is delivered to `OPERATIONS`, not `SIGNALS`.
- Missing/disabled destination configuration affects only that destination.
- Existing Telegram formatting, escaping and error isolation continue to work.
- No Telegram-specific destination details leak into scheduler job policies or notification templates.
- Tests verify routing and configuration for both channels.
