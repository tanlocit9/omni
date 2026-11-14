package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolJobMessage;
import com.omni.platform.shared.executions.WorkType;

class OutputDeclarationsTest {
    private ExpandedJobMessage work(String type, String key, Map<String, Object> parameters) {
        var command =
                new SymbolJobMessage(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "test",
                        WorkType.SYMBOL,
                        key,
                        key,
                        null,
                        null,
                        Map.of());
        return new ExpandedJobMessage(
                command, type, LocalDate.of(2026, 11, 2), parameters, "work-id");
    }

    @Test
    void disabledEnrollmentAndUnmigratedProtocolsDoNotAcquireCompletionContracts() {
        assertThatThrownBy(() -> new OutputDeclarations(true, false))
                .isInstanceOf(IllegalArgumentException.class);
        var disabled = new OutputDeclarations(false, false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.required(work("SYNC_STOCK_PRICE", "UPCOM-HD6", Map.of()))).isEmpty();
        var enabled = new OutputDeclarations(true, true);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.required(work("SYNC_SYMBOLS", "UPCOM", Map.of()))).isEmpty();
        assertThat(enabled.metadata(UUID.randomUUID(), List.of())).isEmpty();
    }

    @Test
    void supportedWorkersReceiveExactNormalizedLogicalPartitions() {
        var declarations = new OutputDeclarations(true, true);
        assertThat(declarations.required(work("SYNC_STOCK_PRICE", "UPCOM-HD6", Map.of())))
                .containsExactly(
                        new OutputRegistrationEvent.LogicalOutput(
                                "eod", Map.of("exchange", "upcom", "code", "hd6")));
        assertThat(
                        declarations.required(
                                work(
                                        "SYNC_INDICATORS",
                                        "UPCOM-HD6",
                                        Map.of("indicatorSource", "AD_CLOSE", "timeframe", "1D"))))
                .containsExactly(
                        new OutputRegistrationEvent.LogicalOutput(
                                "indicators",
                                Map.of(
                                        "exchange",
                                        "upcom",
                                        "code",
                                        "hd6",
                                        "source",
                                        "ad_close",
                                        "timeframe",
                                        "1d")));
        var expected =
                new OutputRegistrationEvent.LogicalOutput(
                        "signals",
                        Map.of(
                                "exchange",
                                "upcom",
                                "strategy",
                                "trend_momentum_v1",
                                "timeframe",
                                "1d"));
        var parameters = Map.<String, Object>of("strategy", "TREND_MOMENTUM_V1", "timeframe", "1D");
        assertThat(declarations.required(work("SYNC_SIGNALS", "UPCOM-HD6", parameters)))
                .containsExactly(expected);
        assertThat(declarations.required(work("EVALUATE_SIGNALS", "UPCOM", parameters)))
                .containsExactly(expected);
    }

    @Test
    void malformedWorkOrMissingTypedParametersCannotEnroll() {
        var declarations = new OutputDeclarations(true, true);
        assertThatThrownBy(() -> declarations.required(work("SYNC_STOCK_PRICE", "HD6", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        for (Map<String, Object> parameters :
                List.of(
                        Map.<String, Object>of(),
                        Map.<String, Object>of("strategy", " "),
                        Map.<String, Object>of("strategy", 1))) {
            assertThatThrownBy(
                            () ->
                                    declarations.required(
                                            work("SYNC_SIGNALS", "UPCOM-HD6", parameters)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strategy");
        }
    }

    @Test
    void completionIdsBindAttemptAndOutputDeclarationWithoutPhysicalPaths() {
        var declarations = new OutputDeclarations(true, true);
        var outputs = declarations.required(work("SYNC_STOCK_PRICE", "UPCOM-HD6", Map.of()));
        var attempt = UUID.randomUUID();
        var metadata = declarations.metadata(attempt, outputs);
        var contract = (Map<?, ?>) metadata.get("outputCompletion");
        assertThat(contract.get("atomicAttemptId")).isEqualTo(attempt.toString());
        assertThat(contract.get("requiredOutputs")).isEqualTo(outputs);
        assertThat(UUID.fromString(contract.get("eventId").toString())).isNotEqualTo(attempt);
        assertThat(UUID.fromString(contract.get("registrationId").toString()))
                .isNotEqualTo(attempt);
        assertThat(metadata.toString()).doesNotContain(".parquet", "_output-snapshots");
    }
}
