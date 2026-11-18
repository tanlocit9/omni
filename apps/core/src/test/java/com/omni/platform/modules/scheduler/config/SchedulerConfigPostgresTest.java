package com.omni.platform.modules.scheduler.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.metadata.OutputReadinessProvider;
import io.minio.MinioClient;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchedulerConfigPostgresTest {
    private final MinioClient minio = mock(MinioClient.class);
    private final OutputReadinessProvider readiness = mock(OutputReadinessProvider.class);
    private final SchedulerConfig config = new SchedulerConfig();

    @Test
    void rejectsLegacyOverrideBeforeAnyStorageRead() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        config.manifestReader(
                                minio, new ObjectMapper(), "stock-data", readiness, false));
        verifyNoInteractions(minio, readiness);
    }

    @Test
    void missingProofAndExactDateCoverageUseOnlyPostgres() {
        var ref = DatasetRef.of("eod");
        var day = LocalDate.of(2026, 10, 16);
        when(readiness.readManifest(ref)).thenReturn(Optional.empty());
        when(readiness.coversDate(ref, "version", day)).thenReturn(true);
        var reader =
                config.manifestReader(minio, new ObjectMapper(), "stock-data", readiness, true);
        assertTrue(reader.readManifest(ref).isEmpty());
        assertTrue(reader.coversDate(ref, "version", day));
        verify(readiness).readManifest(ref);
        verify(readiness).coversDate(ref, "version", day);
        verifyNoInteractions(minio);
    }

    @Test
    void databaseFailurePropagatesWithoutJsonFallback() {
        var ref = DatasetRef.of("eod");
        var failure = new IllegalStateException("database unavailable");
        when(readiness.readManifest(ref)).thenThrow(failure);
        var reader =
                config.manifestReader(minio, new ObjectMapper(), "stock-data", readiness, true);
        assertSame(
                failure, assertThrows(IllegalStateException.class, () -> reader.readManifest(ref)));
        verifyNoInteractions(minio);
    }
}
