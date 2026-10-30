package com.omni.platform.modules.scheduler.work;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import com.omni.platform.modules.scheduler.services.JobOperationException;
import tools.jackson.databind.json.JsonMapper;

class AtomicWorkDiagnosticsValidationTest {
    @Test
    void databaseFailureReturnsUnavailableWithoutConnectionDetails() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new java.sql.SQLException("private connection details"));
        var service = new AtomicWorkDiagnosticsService(source, JsonMapper.builder().build());
        assertThatThrownBy(() -> service.snapshot(null, null, null, null, 25, 24))
                .isInstanceOf(JobOperationException.class)
                .hasMessage("Work diagnostics are unavailable; check schema and database health.")
                .matches(error -> ((JobOperationException) error).getStatus()
                        == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void invalidWindowsSizesAndIdentityFailBeforeDatabaseAccess() throws Exception {
        var source = mock(DataSource.class);
        var service = new AtomicWorkDiagnosticsService(source, JsonMapper.builder().build());
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.snapshot(now, null, null, null, 25, 24))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.snapshot(now, now.minusSeconds(1), null, null, 25, 24))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.snapshot(now.minusSeconds(8 * 86400), now, null, null, 25, 24))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.snapshot(null, null, now.plusSeconds(3600), null, 25, 24))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.snapshot(null, null, null, null, 101, 24))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.snapshot(null, null, null, null, 25, 169))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.unfinished(null, null, 0))
                .isInstanceOf(JobOperationException.class);
        assertThatThrownBy(() -> service.work("' OR true --"))
                .isInstanceOf(JobOperationException.class);
        verifyNoInteractions(source);
    }
}
