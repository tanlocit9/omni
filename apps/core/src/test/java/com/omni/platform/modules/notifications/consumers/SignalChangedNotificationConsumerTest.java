package com.omni.platform.modules.notifications.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties.SignalFilterConfig;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SignalChangedNotificationConsumerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("44e8cce7-7197-42d7-93ce-e64d3002e88a");
    private static final UUID PARENT_EXECUTION_ID = UUID.fromString("adf8625c-cb75-42c5-ae99-621566b5b89d");

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void handlePublishesSignalChangedEventForValidAnalyzerMessage() {
        SignalChangedNotificationConsumer consumer = consumer();

        consumer.handle(record(validPayload(), 1L));

        ArgumentCaptor<SignalChangedNotificationEvent> captor =
                ArgumentCaptor.forClass(SignalChangedNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        verify(eventPublisher, never()).publishEvent(any(OperationalNotificationEvent.class));

        SignalChangedNotificationEvent event = captor.getValue();
        assertThat(event.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(event.parentExecutionId()).isEqualTo(PARENT_EXECUTION_ID);
        assertThat(event.symbolKey()).isEqualTo("SET:PTT");
        assertThat(event.previousSignal()).isEqualTo("HOLD");
        assertThat(event.newSignal()).isEqualTo("BUY");
        assertThat(((Number) event.price()).doubleValue()).isEqualTo(34.75);
        assertThat(event.signalDate()).isEqualTo("2026-08-29");
        assertThat(event.reasonCodes()).containsExactly("RSI_OVERSOLD", "MACD_CROSS");
        assertThat(event.strategy()).isEqualTo("momentum-v1");
        assertThat(event.timeframe()).isEqualTo("1d");
        assertThat(event.createdAt()).isEqualTo(Instant.parse("2026-08-29T08:30:00Z"));
        assertThat(event.metadata()).containsEntry("source", "analyzer");
    }

    @Test
    void handlePublishesUnchangedSignalForANewSignalDate() {
        SignalChangedNotificationConsumer consumer = consumerWithStrategies("CONFIRMED_TREND_EQUALS");
        String payload = validPayload()
                .replace("\"strategy\":\"momentum-v1\"", "\"strategy\":\"CONFIRMED_TREND_EQUALS\"")
                .replace("\"signalChanged\":true", "\"signalChanged\":false,\"newSignalDate\":true");

        consumer.handle(record(payload, 2L));

        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));
        verify(eventPublisher, never()).publishEvent(any(OperationalNotificationEvent.class));
    }

    @Test
    void handleIgnoresSignalsFromAnUnselectedStrategy() {
        SignalChangedNotificationConsumer consumer = consumerWithStrategies("CONFIRMED_TREND_EQUALS");

        consumer.handle(record(validPayload(), 2L));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handleFiltersSignalsBySymbol() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"), List.of("HOSE-FPT", "HOSE-VNM"), null, null, null, null, null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: allowed symbol
        consumer.handle(record(validPayload().replace("SET:PTT", "HOSE-FPT"), 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: not in allowed list
        consumer.handle(record(validPayload().replace("SET:PTT", "HOSE-HPG"), 2L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call
    }

    @Test
    void handleFiltersSignalsBySymbolPattern() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"), null, List.of("HOSE-*", "HNX-A*"), null, null, null, null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: matches HOSE-* pattern
        consumer.handle(record(validPayload().replace("SET:PTT", "HOSE-FPT"), 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should pass: matches HNX-A* pattern
        consumer.handle(record(validPayload().replace("SET:PTT", "HNX-ACB"), 2L));
        verify(eventPublisher, times(2)).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: doesn't match patterns
        consumer.handle(record(validPayload().replace("SET:PTT", "UPCOM-ABC"), 3L));
        verify(eventPublisher, times(2)).publishEvent(any(SignalChangedNotificationEvent.class)); // still 2 calls
    }

    @Test
    void handleFiltersSignalsByDirection() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"), null, null, List.of("BUY", "SELL"), null, null, null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: BUY signal
        consumer.handle(record(validPayload(), 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: HOLD signal
        consumer.handle(record(validPayload().replace("\"newSignal\":\"BUY\"", "\"newSignal\":\"HOLD\""), 2L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call
    }

    @Test
    void handleFiltersSignalsByTimeframe() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"), null, null, null, List.of("1d"), null, null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: 1d timeframe
        consumer.handle(record(validPayload(), 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: 4h timeframe
        consumer.handle(record(validPayload().replace("\"timeframe\":\"1d\"", "\"timeframe\":\"4h\""), 2L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call
    }

    @Test
    void handleFiltersSignalsByMinScore() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"), null, null, null, null, 0.7, null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: score 0.91 >= 0.7
        consumer.handle(record(validPayload(), 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: score 0.5 < 0.7
        consumer.handle(record(validPayload().replace("\"score\":0.91", "\"score\":0.5"), 2L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call
    }

    @Test
    void handleAppliesMultipleFiltersTogether() {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of("momentum-v1"),
                null,
                List.of("HOSE-*"),
                List.of("BUY"),
                List.of("1d"),
                0.7,
                null);
        SignalChangedNotificationConsumer consumer = consumerWithFilter(filter);

        // Should pass: all filters match
        String validSignal = validPayload()
                .replace("SET:PTT", "HOSE-FPT")
                .replace("\"newSignal\":\"BUY\"", "\"newSignal\":\"BUY\"")
                .replace("\"timeframe\":\"1d\"", "\"timeframe\":\"1d\"")
                .replace("\"score\":0.91", "\"score\":0.8");
        consumer.handle(record(validSignal, 1L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class));

        // Should be filtered: wrong symbol pattern
        consumer.handle(record(validSignal.replace("HOSE-FPT", "HNX-ACB"), 2L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call

        // Should be filtered: wrong direction
        consumer.handle(record(validSignal.replace("\"newSignal\":\"BUY\"", "\"newSignal\":\"SELL\""), 3L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call

        // Should be filtered: score too low
        consumer.handle(record(validSignal.replace("\"score\":0.8", "\"score\":0.6"), 4L));
        verify(eventPublisher).publishEvent(any(SignalChangedNotificationEvent.class)); // still 1 call
    }

    @Test
    void handlePublishesOperationalFailureAndRethrowsMalformedJson() {
        SignalChangedNotificationConsumer consumer = consumer();

        assertThatThrownBy(() -> consumer.handle(record("not-json", 2L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to process signal notification");

        verify(eventPublisher).publishEvent(any(OperationalNotificationEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SignalChangedNotificationEvent.class));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidContracts")
    void handlePublishesOperationalFailureAndRethrowsInvalidContract(
            String description, String validField, String invalidField, long offset) {
        SignalChangedNotificationConsumer consumer = consumer();
        String payload = validPayload().replace(validField, invalidField);

        assertThatThrownBy(() -> consumer.handle(record(payload, offset)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to process signal notification")
                .hasCauseInstanceOf(IllegalArgumentException.class);

        verify(eventPublisher).publishEvent(any(OperationalNotificationEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SignalChangedNotificationEvent.class));
    }

    private static Stream<Arguments> invalidContracts() {
        return Stream.of(
                Arguments.of("unsupported type", "\"type\":\"SIGNAL_CHANGED\"", "\"type\":\"SIGNAL_CREATED\"", 10L),
                Arguments.of("signalChanged false without a new date", "\"signalChanged\":true", "\"signalChanged\":false", 11L),
                Arguments.of("both event reasons false", "\"signalChanged\":true", "\"signalChanged\":false,\"newSignalDate\":false", 19L),
                Arguments.of("null executionId", "\"executionId\":\"44e8cce7-7197-42d7-93ce-e64d3002e88a\"", "\"executionId\":null", 12L),
                Arguments.of("null parentExecutionId", "\"parentExecutionId\":\"adf8625c-cb75-42c5-ae99-621566b5b89d\"", "\"parentExecutionId\":null", 13L),
                Arguments.of("blank symbolKey", "\"symbolKey\":\"SET:PTT\"", "\"symbolKey\":\"  \"", 14L),
                Arguments.of("null symbolKey", "\"symbolKey\":\"SET:PTT\"", "\"symbolKey\":null", 15L),
                Arguments.of("blank newSignal", "\"newSignal\":\"BUY\"", "\"newSignal\":\"\"", 16L),
                Arguments.of("null newSignal", "\"newSignal\":\"BUY\"", "\"newSignal\":null", 17L),
                Arguments.of("null createdAt", "\"createdAt\":\"2026-08-29T08:30:00Z\"", "\"createdAt\":null", 18L));
    }

    private SignalChangedNotificationConsumer consumer() {
        return consumerWithStrategies("momentum-v1");
    }

    private SignalChangedNotificationConsumer consumerWithStrategies(String... strategies) {
        SignalFilterConfig filter = new SignalFilterConfig(
                List.of(strategies), null, null, null, null, null, null);
        return consumerWithFilter(filter);
    }

    private SignalChangedNotificationConsumer consumerWithFilter(SignalFilterConfig filter) {
        TelegramNotificationProperties properties = new TelegramNotificationProperties(
                true, "token", "ops-chat", "signals-chat", "HTML",
                "https://api.telegram.org", Duration.ofMinutes(5), 10000,
                "Asia/Bangkok", true, filter);
        return new SignalChangedNotificationConsumer(eventPublisher, jsonMapper, properties);
    }

    private ConsumerRecord<String, String> record(String payload, long offset) {
        return new ConsumerRecord<>("topic-signal-notifications", 0, offset, "SET:PTT", payload);
    }

    private static String validPayload() {
        return """
                {
                  "type":"SIGNAL_CHANGED",
                  "executionId":"44e8cce7-7197-42d7-93ce-e64d3002e88a",
                  "parentExecutionId":"adf8625c-cb75-42c5-ae99-621566b5b89d",
                  "symbolKey":"SET:PTT",
                  "previousSignal":"HOLD",
                  "newSignal":"BUY",
                  "price":34.75,
                  "signalDate":"2026-08-29",
                  "reasonCodes":["RSI_OVERSOLD","MACD_CROSS"],
                  "score":0.91,
                  "strategy":"momentum-v1",
                  "timeframe":"1d",
                  "signalChanged":true,
                  "createdAt":"2026-08-29T08:30:00Z",
                  "metadata":{"source":"analyzer"}
                }
                """;
    }
}
