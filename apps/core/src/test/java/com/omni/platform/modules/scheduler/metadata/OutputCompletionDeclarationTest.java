package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutputCompletionDeclarationTest {
    private final UUID execution = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final OutputRegistrationEvent.LogicalOutput output =
            new OutputRegistrationEvent.LogicalOutput(
                    "eod", Map.of("exchange", "hose", "code", "hpg"));

    @Test
    void malformedOrOversizedDeclarationsCannotTouchPersistence() {
        var jdbc = mock(JdbcTemplate.class);
        var gate = new OutputCompletionGate(jdbc, JsonMapper.builder().build());
        var oversized = new ArrayList<OutputRegistrationEvent.LogicalOutput>();
        for (int i = 0; i < 33; i++)
            oversized.add(
                    new OutputRegistrationEvent.LogicalOutput(
                            "eod", Map.of("exchange", "hose", "code", "c" + i)));
        var declarations = new ArrayList<List<OutputRegistrationEvent.LogicalOutput>>();
        declarations.add(null);
        declarations.add(List.of());
        declarations.add(oversized);
        declarations.add(Arrays.asList((OutputRegistrationEvent.LogicalOutput) null));
        declarations.add(List.of(output, output));
        for (var required : declarations)
            assertThatThrownBy(() -> gate.enroll(execution, attempt, required, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        var reasons = new ArrayList<List<String>>();
        reasons.add(null);
        reasons.add(java.util.Collections.nCopies(33, "EMPTY"));
        reasons.add(Arrays.asList((String) null));
        reasons.add(List.of(" "));
        reasons.add(List.of("x".repeat(501)));
        for (var invalid : reasons)
            assertThatThrownBy(() -> gate.enroll(execution, attempt, List.of(output), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"terminal", "dispatched", "shadow", "conflict", "valid", "pinned"})
    void enrollmentCannotRebindTerminalDispatchedOrAlreadyObservedWork(String state) {
        JdbcTemplate jdbc =
                mock(
                        JdbcTemplate.class,
                        call -> {
                            String method = call.getMethod().getName();
                            String sql =
                                    call.getArguments().length > 0
                                                    && call.getArgument(0) instanceof String text
                                            ? text
                                            : "";
                            if (method.equals("queryForList"))
                                return state.equals("terminal")
                                        ? List.of()
                                        : List.of(Map.of("id", execution));
                            if (method.equals("queryForObject")) {
                                if (sql.contains("scheduler_outbox_messages"))
                                    return state.equals("dispatched");
                                if (sql.contains("FROM output_registrations"))
                                    return state.equals("shadow");
                                if (sql.contains("FROM output_storage_heads"))
                                    return state.equals("pinned");
                                return !state.equals("conflict");
                            }
                            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                        });
        var gate = new OutputCompletionGate(jdbc, JsonMapper.builder().build());
        if (state.equals("valid"))
            assertThatCode(
                            () ->
                                    gate.enroll(
                                            execution,
                                            attempt,
                                            List.of(output),
                                            List.of("OUTPUT_UNCHANGED")))
                    .doesNotThrowAnyException();
        else
            assertThatThrownBy(() -> gate.enroll(execution, attempt, List.of(output), List.of()))
                    .isInstanceOf(
                            state.equals("pinned")
                                    ? IllegalStateException.class
                                    : IllegalArgumentException.class);
    }
}
