package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.shared.executions.WorkType;

class JobServiceStatusCompatibilityTest {
    private final JobExecutionHistoryRepository histories =
            mock(JobExecutionHistoryRepository.class);
    private final JobService service =
            new JobService(null, histories, null, null, null, null, null);

    @Test
    void legacyStatusCountersAcceptNumericTextNullAndInsertedFallbackWithExplicitCountPrecedence() {
        List<Map<String, Object>> metadata =
                Arrays.asList(
                        null,
                        Map.of(),
                        Map.of("recordsProcessed", 4),
                        Map.of("recordsProcessed", "5"),
                        Map.of("recordsProcessed", " ", "recordsInserted", "6"),
                        Map.of("recordsInserted", 7),
                        new HashMap<>());
        metadata.getLast().put("recordsProcessed", null);
        metadata.getLast().put("recordsInserted", null);
        List<Integer> expected = List.of(0, 0, 4, 5, 6, 7, 0);
        for (int index = 0; index < metadata.size(); index++) {
            var work = execution();
            when(histories.findById(work.getId())).thenReturn(Optional.of(work));
            service.applyStatus(
                    message(work, WorkType.GLOBAL, "SYNC_METADATA", metadata.get(index), null));
            assertThat(work.getRecordsSynced()).isEqualTo(expected.get(index));
            assertThat(work.getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(work.getMetaJson())
                    .containsEntry("workType", "GLOBAL")
                    .containsEntry("workKey", "SYNC_METADATA");
        }
        var work = execution();
        when(histories.findById(work.getId())).thenReturn(Optional.of(work));
        service.applyStatus(
                message(
                        work,
                        WorkType.GLOBAL,
                        "SYNC_METADATA",
                        Map.of("recordsProcessed", "50", "recordsInserted", 60),
                        3));
        assertThat(work.getRecordsSynced()).isEqualTo(3);
    }

    @Test
    void invalidCounterTypesAreRejectedBeforeLoadingOrMutatingExecutions() {
        for (String counter : List.of("recordsProcessed", "recordsInserted")) {
            for (Object value : List.of("invalid", true, List.of(1))) {
                var work = execution();
                service.applyStatus(
                        message(
                                work,
                                WorkType.GLOBAL,
                                "SYNC_METADATA",
                                Map.of(counter, value),
                                null));
                assertThat(work.getStatus()).isEqualTo(JobStatus.PENDING);
            }
        }
        verifyNoInteractions(histories);
    }

    @Test
    void missingOrForeignStatusWorkIdentityCannotUpdatePreparedWork() {
        for (int invalid = 0; invalid < 6; invalid++) {
            var work = execution();
            when(histories.findById(work.getId())).thenReturn(Optional.of(work));
            if (invalid >= 3) work.setParentLogId(UUID.randomUUID());
            if (invalid == 4) {
                work.setMetaJson(Map.of("workType", "SYMBOL", "workKey", "SYNC_METADATA"));
            }
            if (invalid == 5) work.setMetaJson(null);
            service.applyStatus(
                    message(
                            work,
                            invalid == 0 ? null : WorkType.GLOBAL,
                            invalid == 1
                                    ? null
                                    : invalid == 2 ? " " : invalid == 3 ? "OTHER" : "SYNC_METADATA",
                            Map.of(),
                            0));
            assertThat(work.getStatus()).isEqualTo(JobStatus.PENDING);
        }
        verify(histories, never()).saveAndFlush(any());
    }

    @Test
    void directFailureAndSuccessTransitionsDoNotReopenTerminalWork() {
        var failed = execution();
        service.markExecutionFailed(failed, "write failed", Instant.EPOCH, null);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getRecordsSynced()).isZero();
        assertThat(failed.getRecordsSkipped()).isEqualTo(1);
        service.markExecutionSuccess(failed, 10, 0, Instant.EPOCH, Map.of());
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        var successful = execution();
        service.markExecutionSuccess(successful, 10, 0, Instant.EPOCH, Map.of("result", "ready"));
        service.markExecutionFailed(
                successful, "late failure", Instant.EPOCH, Map.of("result", "changed"));
        assertThat(successful.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(successful.getMetaJson()).containsEntry("result", "ready");
        verify(histories, never()).saveAndFlush(any());
    }

    private static JobExecutionHistory execution() {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        var work = new JobExecutionHistory();
        work.setId(UUID.randomUUID());
        work.setJob(job);
        work.setStatus(JobStatus.PENDING);
        work.setMetaJson(Map.of("workType", "GLOBAL", "workKey", "SYNC_METADATA"));
        return work;
    }

    private static JobStatusMessage message(
            JobExecutionHistory work,
            WorkType type,
            String key,
            Map<String, Object> metadata,
            Integer count) {
        return new JobStatusMessage(
                work.getJob().getId().toString(),
                work.getId().toString(),
                null,
                type,
                key,
                "RUNNING",
                metadata,
                null,
                null,
                null,
                0L,
                null,
                count);
    }
}
