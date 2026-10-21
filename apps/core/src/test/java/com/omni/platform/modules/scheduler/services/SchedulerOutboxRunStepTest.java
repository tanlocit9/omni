package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SyncMetadataJobMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import tools.jackson.databind.json.JsonMapper;

class SchedulerOutboxRunStepTest {
    final SchedulerOutboxRepository repository = mock(SchedulerOutboxRepository.class);
    final JobExecutionHistoryRepository histories = mock(JobExecutionHistoryRepository.class);
    final KafkaPublisher publisher = mock(KafkaPublisher.class);
    final SchedulerOutboxService service =
            new SchedulerOutboxService(
                    repository, histories, publisher, JsonMapper.builder().build());
    final JobExecutionHistory parent = execution();

    @Test
    void validatesCompleteRootAndChildMessageMembershipWithoutPerChildReads() {
        var direct = message(parent.getId(), null, parent.getJob().getId(), "SYNC_METADATA");
        when(publisher.serialize(direct.payload())).thenReturn("{}");
        service.enqueueRunStep(parent, "jobs", List.of(direct), Instant.EPOCH, List.of());
        var child = child();
        var work = message(child.getId(), parent.getId(), parent.getJob().getId(), "SYNC_METADATA");
        when(publisher.serialize(work.payload())).thenReturn("{}");
        service.enqueueRunStep(parent, "jobs", List.of(work), Instant.EPOCH, List.of(child));
        verifyNoInteractions(histories);
        verify(repository, org.mockito.Mockito.times(2))
                .saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMalformedDuplicateForeignMissingAndReboundMessages() {
        var direct = message(parent.getId(), null, parent.getJob().getId(), "SYNC_METADATA");
        for (List<KafkaMessage> invalid :
                List.of(
                        List.of(new KafkaMessage("key", Map.of())),
                        List.of(message(parent.getId(), null, UUID.randomUUID(), "SYNC_METADATA")),
                        List.of(direct, direct),
                        List.of(
                                message(
                                        UUID.randomUUID(),
                                        null,
                                        parent.getJob().getId(),
                                        "SYNC_METADATA")),
                        List.of(
                                message(
                                        parent.getId(),
                                        UUID.randomUUID(),
                                        parent.getJob().getId(),
                                        "SYNC_METADATA")),
                        List.of(message(parent.getId(), null, parent.getJob().getId(), "other")))) {
            assertThatThrownBy(
                            () ->
                                    service.enqueueRunStep(
                                            parent, "jobs", invalid, Instant.EPOCH, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var child = child();
        assertThatThrownBy(
                        () ->
                                service.enqueueRunStep(
                                        parent, "jobs", List.of(), Instant.EPOCH, List.of(child)))
                .hasMessageContaining("Incomplete");
        assertThatThrownBy(
                        () ->
                                service.enqueueRunStep(
                                        parent,
                                        "jobs",
                                        List.of(direct),
                                        Instant.EPOCH,
                                        List.of(child)))
                .hasMessageContaining("execution mismatch");
        assertThatThrownBy(
                        () ->
                                service.enqueueRunStep(
                                        parent,
                                        "jobs",
                                        List.of(
                                                message(
                                                        child.getId(),
                                                        null,
                                                        parent.getJob().getId(),
                                                        "SYNC_METADATA")),
                                        Instant.EPOCH,
                                        List.of(child)))
                .hasMessageContaining("parent mismatch");
        for (int field = 0; field < 3; field++) {
            var foreign = child();
            if (field == 0) foreign.setParentLogId(UUID.randomUUID());
            if (field == 1) foreign.setJobRunId(UUID.randomUUID());
            if (field == 2) foreign.setJobRunStepIndex(99);
            assertThatThrownBy(
                            () ->
                                    service.enqueueRunStep(
                                            parent,
                                            "jobs",
                                            List.of(),
                                            Instant.EPOCH,
                                            List.of(foreign)))
                    .hasMessageContaining("ownership mismatch");
        }
        assertThatThrownBy(
                        () ->
                                service.enqueueRunStep(
                                        parent,
                                        "jobs",
                                        java.util.Collections.nCopies(5001, direct),
                                        Instant.EPOCH,
                                        List.of()))
                .hasMessageContaining("WORK_BOUND");
        verifyNoInteractions(histories, repository);
    }

    private JobExecutionHistory child() {
        var child = execution();
        child.setJob(parent.getJob());
        child.setJobRunId(parent.getJobRunId());
        child.setJobRunStepIndex(parent.getJobRunStepIndex());
        child.setParentLogId(parent.getId());
        return child;
    }

    private static JobExecutionHistory execution() {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        var execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setJobRunId(UUID.randomUUID());
        execution.setJobRunStepIndex(0);
        execution.setUsedSource(JobDefinition.DataSource.VCI);
        execution.setMetaJson(Map.of("workType", "GLOBAL", "workKey", "SYNC_METADATA"));
        return execution;
    }

    private static KafkaMessage message(UUID execution, UUID parent, UUID definition, String key) {
        return new KafkaMessage(
                "metadata",
                new SyncMetadataJobMessage(
                        definition,
                        execution,
                        parent,
                        "VCI",
                        WorkType.GLOBAL,
                        key,
                        null,
                        Map.of()));
    }
}
