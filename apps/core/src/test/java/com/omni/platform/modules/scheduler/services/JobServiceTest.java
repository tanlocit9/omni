package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.notifications.templates.JobNotificationTemplate;
import com.omni.platform.modules.scheduler.notifications.DefaultJobNotificationPolicy;
import com.omni.platform.modules.scheduler.notifications.JobNotificationPolicyRegistry;
import com.omni.platform.modules.scheduler.notifications.SignalDigestNotificationPolicy;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobDefinitionRepository jobDefinitionRepository;

    @Mock
    private JobExecutionHistoryRepository historyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SchedulerOutboxService schedulerOutboxService;

    private JobService service;

    @BeforeEach
    void setUp() {
        JobNotificationTemplate template = new JobNotificationTemplate();
        DefaultJobNotificationPolicy defaultPolicy = new DefaultJobNotificationPolicy(template);
        JobNotificationPolicyRegistry policyRegistry = new JobNotificationPolicyRegistry(
                List.of(new SignalDigestNotificationPolicy(defaultPolicy)),
                defaultPolicy);
        service = new JobService(
                jobDefinitionRepository,
                historyRepository,
                eventPublisher,
                policyRegistry,
                schedulerOutboxService);
    }

    @Test
    void createChildExecutionPersistsOnlyCanonicalWorkIdentity() {
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        when(historyRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(historyRepository.save(any(JobExecutionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        JobExecutionHistory child = service.createChildExecution(
                parent.getId(),
                WorkIdentity.of(WorkType.SYMBOL, "HOSE-HPG"),
                Map.of("symbolKey", "legacy", "workType", "SECTOR", "workKey", "wrong"),
                Instant.parse("2026-08-27T03:00:00Z"));

        assertThat(child.getMetaJson())
                .containsEntry("workType", "SYMBOL")
                .containsEntry("workKey", "HOSE-HPG")
                .doesNotContainKey("symbolKey");
    }

    @Test
    void applyStatusRejectsMismatchedChildWorkIdentity() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory child = execution(JobStatus.RUNNING, parentId);
        when(historyRepository.findById(child.getId())).thenReturn(Optional.of(child));

        JobStatusMessage mismatched = new JobStatusMessage(
                child.getJob().getId().toString(),
                child.getId().toString(),
                parentId.toString(),
                WorkType.SYMBOL,
                "HNX-MISMATCH",
                "SUCCESS",
                Map.of(),
                null,
                Instant.parse("2026-08-27T03:00:00Z"),
                Instant.parse("2026-08-27T03:01:00Z"),
                60_000L,
                null,
                1);

        service.applyStatus(mismatched);

        assertThat(child.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(historyRepository, never()).saveAndFlush(child);
    }

    @Test
    void applyStatusPublishesStandaloneSuccessOnceOnTerminalTransition() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);
        when(historyRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(historyRepository.saveAndFlush(execution)).thenReturn(execution);

        service.applyStatus(message(execution.getId(), null, "SUCCESS", 7, null));

        assertThat(execution.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(execution.getRecordsSynced()).isEqualTo(7);
        ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor.forClass(OperationalNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(captor.getValue().title()).isEqualTo("Job completed: Test job");
    }

    @Test
    void applyStatusPublishesStandaloneFailureOnFailedTransition() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);
        when(historyRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(historyRepository.saveAndFlush(execution)).thenReturn(execution);

        service.applyStatus(message(execution.getId(), null, "FAILED", 0, "boom"));

        assertThat(execution.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(execution.getError()).isEqualTo("boom");
        ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor.forClass(OperationalNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(captor.getValue().message()).isEqualTo("boom");
    }

    @Test
    void applyStatusNormalizesIncomingErrorToFailed() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);
        when(historyRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(historyRepository.saveAndFlush(execution)).thenReturn(execution);

        service.applyStatus(message(execution.getId(), null, "ERROR", 0, "error"));

        assertThat(execution.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(eventPublisher).publishEvent(any(OperationalNotificationEvent.class));
    }

    @Test
    void applyStatusIgnoresDuplicateTerminalMessage() {
        JobExecutionHistory execution = execution(JobStatus.SUCCESS, null);
        when(historyRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        service.applyStatus(message(execution.getId(), null, "SUCCESS", 7, null));

        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusDoesNotReopenTerminalExecution() {
        JobExecutionHistory execution = execution(JobStatus.FAILED, null);
        when(historyRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        service.applyStatus(message(execution.getId(), null, "RUNNING", 0, null));

        assertThat(execution.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusIgnoresMalformedExecutionId() {
        service.applyStatus(message("not-a-uuid", null, "SUCCESS", 7, null, Map.of("recordsInserted", "9")));

        verify(historyRepository, never()).findById(any());
        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusIgnoresUnknownExecutionId() {
        UUID executionId = UUID.randomUUID();
        when(historyRepository.findById(executionId)).thenReturn(Optional.empty());

        service.applyStatus(message(executionId, null, "SUCCESS", 7, null));

        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusIgnoresInvalidStatus() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);

        service.applyStatus(message(execution.getId(), null, "NOT_A_STATUS", 7, null));

        verify(historyRepository, never()).findById(any());
        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusIgnoresMalformedParentExecutionId() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);

        service.applyStatus(message(execution.getId().toString(), "not-a-uuid", "SUCCESS", 7, null,
                Map.of("recordsInserted", "9")));

        verify(historyRepository, never()).findById(any());
        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusIgnoresNonNumericMetadataCounters() {
        JobExecutionHistory execution = execution(JobStatus.RUNNING, null);

        service.applyStatus(message(execution.getId().toString(), null, "SUCCESS", null, null,
                Map.of("recordsInserted", "not-a-number")));

        verify(historyRepository, never()).findById(any());
        verify(historyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyStatusAggregatesPersistedParentAndSuppressesChildNotification() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory child = execution(JobStatus.RUNNING, parentId);
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        JobExecutionHistory sibling = execution(JobStatus.SUCCESS, parentId);
        when(historyRepository.findById(child.getId())).thenReturn(Optional.of(child));
        when(historyRepository.saveAndFlush(child)).thenReturn(child);
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(child, sibling));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.applyStatus(message(child.getId(), UUID.randomUUID(), "SUCCESS", 3, null));

        assertThat(parent.getStatus()).isEqualTo(JobStatus.SUCCESS);
        ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor.forClass(OperationalNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().message()).isEqualTo("2/2 tasks completed successfully");
    }

    @Test
    void aggregateParentKeepsRunningWhenAnyChildIsRunning() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        JobExecutionHistory success = execution(JobStatus.SUCCESS, parentId);
        success.setRecordsSynced(4);
        JobExecutionHistory running = execution(JobStatus.RUNNING, parentId);
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(success, running));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        assertThat(parent.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(parent.getRecordsSynced()).isEqualTo(4);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aggregateParentPublishesSuccessDigestWhenAllChildrenSucceeded() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        JobExecutionHistory first = execution(JobStatus.SUCCESS, parentId);
        first.setRecordsSynced(4);
        first.setStartedAt(Instant.parse("2026-07-28T00:00:00Z"));
        first.setFinishedAt(Instant.parse("2026-07-28T00:01:00Z"));
        JobExecutionHistory second = execution(JobStatus.SUCCESS, parentId);
        second.setRecordsSynced(6);
        second.setStartedAt(Instant.parse("2026-07-28T00:00:30Z"));
        second.setFinishedAt(Instant.parse("2026-07-28T00:02:00Z"));
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(first, second));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        assertThat(parent.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(parent.getRecordsSynced()).isEqualTo(10);
        assertThat(parent.getFinishedAt()).isEqualTo(Instant.parse("2026-07-28T00:02:00Z"));
        assertThat(parent.getMetaJson()).containsEntry("childCount", "2")
                .containsEntry("successCount", "2")
                .containsEntry("failedCount", "0");
        verify(eventPublisher).publishEvent(any(OperationalNotificationEvent.class));
    }

    @Test
    void aggregateSignalParentPublishesSignalDigestWhenSuccessfulChildrenChanged() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        parent.getJob().setJobType(JobType.SYNC_SIGNALS);
        parent.getJob().setTitle("Sync market signals - daily BANKS");
        JobExecutionHistory changed = execution(JobStatus.SUCCESS, parentId);
        changed.setRecordsSynced(1);
        changed.setMetaJson(Map.ofEntries(
                Map.entry("workType", "SYMBOL"),
                Map.entry("workKey", "HOSE-HPG"),
                Map.entry("signalChanged", "true"),
                Map.entry("previousSignal", "NEUTRAL"),
                Map.entry("newSignal", "BULLISH"),
                Map.entry("price", "28000.0"),
                Map.entry("signalDate", "2026-07-28"),
                Map.entry("strategy", "TREND_MOMENTUM_V1"),
                Map.entry("timeframe", "1d"),
                Map.entry("score", "4"),
                Map.entry("reasonCodes", List.of("PRICE_ABOVE_MA50", "SCORE_4"))));
        JobExecutionHistory unchanged = execution(JobStatus.SUCCESS, parentId);
        unchanged.setMetaJson(Map.of("workType", "SYMBOL", "workKey", "HOSE-VCB", "signalChanged", "false"));
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(changed, unchanged));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SignalDigestNotificationEvent.class);
        assertThat(captor.getAllValues()).noneMatch(OperationalNotificationEvent.class::isInstance);
        assertThat(captor.getAllValues()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(SignalDigestNotificationEvent.class);
            SignalDigestNotificationEvent digest = (SignalDigestNotificationEvent) event;
            assertThat(digest.parentExecutionId()).isEqualTo(parentId);
            assertThat(digest.jobTitle()).isEqualTo("Sync market signals - daily BANKS");
            assertThat(digest.strategy()).isEqualTo("TREND_MOMENTUM_V1");
            assertThat(digest.timeframe()).isEqualTo("1d");
            assertThat(digest.changedCount()).isEqualTo(1);
            assertThat(digest.items()).singleElement().satisfies(item -> {
                assertThat(item.symbolKey()).isEqualTo("HOSE-HPG");
                assertThat(item.previousSignal()).isEqualTo("NEUTRAL");
                assertThat(item.newSignal()).isEqualTo("BULLISH");
            });
        });
    }

    @Test
    void aggregateSignalParentSuppressesSignalDigestWhenNoChildrenChanged() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        parent.getJob().setJobType(JobType.SYNC_SIGNALS);
        JobExecutionHistory unchanged = execution(JobStatus.SUCCESS, parentId);
        unchanged.setMetaJson(Map.of("workType", "SYMBOL", "workKey", "HOSE-HPG", "signalChanged", "false"));
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(unchanged));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(OperationalNotificationEvent.class);
    }

    @Test
    void aggregateSignalParentSuppressesSignalDigestWhenParentFailed() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        parent.getJob().setJobType(JobType.SYNC_SIGNALS);
        JobExecutionHistory changed = execution(JobStatus.SUCCESS, parentId);
        changed.setMetaJson(Map.of("workType", "SYMBOL", "workKey", "HOSE-HPG", "signalChanged", "true"));
        JobExecutionHistory failed = execution(JobStatus.FAILED, parentId);
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(changed, failed));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(OperationalNotificationEvent.class);
    }

    @Test
    void aggregateParentFailsWhenAnyChildFailedOrErrored() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.RUNNING, null);
        parent.setId(parentId);
        JobExecutionHistory success = execution(JobStatus.SUCCESS, parentId);
        JobExecutionHistory failed = execution(JobStatus.FAILED, parentId);
        JobExecutionHistory errored = execution(JobStatus.ERROR, parentId);
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(historyRepository.findAllByParentLogId(parentId)).thenReturn(List.of(success, failed, errored));
        when(historyRepository.saveAndFlush(parent)).thenReturn(parent);

        service.aggregateParentExecution(parentId);

        assertThat(parent.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(parent.getError()).isEqualTo("2/3 tasks failed");
        assertThat(parent.getMetaJson()).containsEntry("failedCount", "2");
        ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor.forClass(OperationalNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(captor.getValue().message()).isEqualTo("2/3 tasks failed");
    }

    @Test
    void aggregateParentDoesNotPublishWhenParentAlreadyTerminal() {
        UUID parentId = UUID.randomUUID();
        JobExecutionHistory parent = execution(JobStatus.SUCCESS, null);
        parent.setId(parentId);
        when(historyRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));

        service.aggregateParentExecution(parentId);

        verify(historyRepository, never()).findAllByParentLogId(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void prepareClaimedExecutionPersistsLogicalApprovedInputs() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        JobDefinition job = execution(JobStatus.PENDING, null).getJob();
        UUID token = UUID.randomUUID();
        job.setClaimToken(token);
        job.setClaimedBy("core-a");
        job.setClaimedAt(now);
        job.setClaimUntil(now.plusSeconds(120));
        SchedulerClaim claim = new SchedulerClaim(
                job.getId(),
                token,
                "core-a",
                now,
                now.plusSeconds(120),
                now);
        DatasetRef eodRef = DatasetRef.of(
                "eod",
                Map.of("exchange", "hose", "code", "hpg"));

        JobExecutionHistory prepared = service.prepareClaimedExecution(
                job,
                claim,
                now,
                Map.of(eodRef, "sha256:eod-hpg"));

        assertThat(prepared.getMetaJson()).containsOnlyKeys("approvedInputs");
        assertThat(prepared.getMetaJson().get("approvedInputs"))
                .isEqualTo(List.of(Map.of(
                        "dataset", "eod",
                        "partition", Map.of(
                                "exchange", "hose",
                                "code", "hpg"),
                        "dataVersion", "sha256:eod-hpg")));
        assertThat(prepared.getMetaJson().toString()).doesNotContain("path");
        verify(historyRepository, org.mockito.Mockito.times(2)).save(prepared);
    }

    @Test
    void markParentWithNoChildrenIsSilent() {
        JobExecutionHistory parent = execution(JobStatus.PENDING, null);
        Instant now = Instant.parse("2026-07-28T00:00:00Z");

        service.markParentWithNoChildren(parent, now);

        assertThat(parent.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(parent.getRecordsSynced()).isZero();
        assertThat(parent.getRecordsSkipped()).isZero();
        assertThat(parent.getMetaJson()).containsEntry("childCount", "0")
                .containsEntry("successCount", "0")
                .containsEntry("failedCount", "0");
        verify(historyRepository).save(parent);
        verify(eventPublisher, never()).publishEvent(any());
    }

    private JobStatusMessage message(
            UUID executionId,
            UUID parentExecutionId,
            String status,
            Integer recordsProcessed,
            String errorMessage) {
        return message(
                executionId.toString(),
                parentExecutionId == null ? null : parentExecutionId.toString(),
                status,
                recordsProcessed,
                errorMessage,
                Map.of("recordsInserted", "9"));
    }

    private JobStatusMessage message(
            String executionId,
            String parentExecutionId,
            String status,
            Integer recordsProcessed,
            String errorMessage,
            Map<String, Object> metaJson) {
        return new JobStatusMessage(
                UUID.randomUUID().toString(),
                executionId,
                parentExecutionId,
                WorkType.SYMBOL,
                "HOSE-HPG",
                status,
                metaJson,
                "offset-1",
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:01:00Z"),
                60_000L,
                errorMessage,
                recordsProcessed);
    }

    private JobExecutionHistory execution(JobStatus status, UUID parentLogId) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setTitle("Test job");
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setSource(DataSource.VND);

        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setUsedSource(DataSource.VND);
        execution.setStatus(status);
        execution.setParentLogId(parentLogId);
        execution.setRecordsSynced(0);
        execution.setRecordsSkipped(0);
        if (parentLogId != null) {
            execution.setMetaJson(Map.of("workType", "SYMBOL", "workKey", "HOSE-HPG"));
        }
        return execution;
    }
}
