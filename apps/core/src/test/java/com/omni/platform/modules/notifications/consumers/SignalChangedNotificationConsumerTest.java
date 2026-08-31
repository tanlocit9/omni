package com.omni.platform.modules.notifications.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
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
                Arguments.of("signalChanged false", "\"signalChanged\":true", "\"signalChanged\":false", 11L),
                Arguments.of("null executionId", "\"executionId\":\"44e8cce7-7197-42d7-93ce-e64d3002e88a\"", "\"executionId\":null", 12L),
                Arguments.of("null parentExecutionId", "\"parentExecutionId\":\"adf8625c-cb75-42c5-ae99-621566b5b89d\"", "\"parentExecutionId\":null", 13L),
                Arguments.of("blank symbolKey", "\"symbolKey\":\"SET:PTT\"", "\"symbolKey\":\"  \"", 14L),
                Arguments.of("null symbolKey", "\"symbolKey\":\"SET:PTT\"", "\"symbolKey\":null", 15L),
                Arguments.of("blank newSignal", "\"newSignal\":\"BUY\"", "\"newSignal\":\"\"", 16L),
                Arguments.of("null newSignal", "\"newSignal\":\"BUY\"", "\"newSignal\":null", 17L),
                Arguments.of("null createdAt", "\"createdAt\":\"2026-08-29T08:30:00Z\"", "\"createdAt\":null", 18L));
    }

    private SignalChangedNotificationConsumer consumer() {
        return new SignalChangedNotificationConsumer(eventPublisher, jsonMapper);
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
