package com.omni.platform.modules.scheduler.consumers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;

import tools.jackson.databind.json.JsonMapper;

class SymbolUpsertConsumerTest {
    @Test
    void emptyAndNullBatchesDoNotDeactivateSymbolsOrTouchExecutionMembership() {
        var symbols = mock(SymbolRepository.class);
        var jobs = mock(JobService.class);
        var consumer =
                new SymbolUpsertConsumer(
                        mock(ApplicationEventPublisher.class), symbols, jobs, new JsonMapper());
        for (String batch : new String[] {"[]", "null"}) {
            consumer.handleSymbolUpsert(
                    record(
                            "{\"exchange\":\"HOSE\",\"expectedCount\":0,\"actualCount\":0,\"symbols\":"
                                    + batch
                                    + "}"));
        }
        verifyNoInteractions(symbols, jobs);
    }

    @Test
    void failedLegacyUpsertRetainsChildFailureTrackingAndRejectsTheBatch() {
        var symbols = mock(SymbolRepository.class);
        var jobs = mock(JobService.class);
        var events = mock(ApplicationEventPublisher.class);
        var child = new JobExecutionHistory();
        when(jobs.createChildExecution(any(), any(), any(), any())).thenReturn(child);
        doThrow(new IllegalStateException("write failed"))
                .when(symbols)
                .upsertOne(anyString(), anyString(), anyString());
        var consumer = new SymbolUpsertConsumer(events, symbols, jobs, new JsonMapper());
        String payload =
                "{\"exchange\":\"HOSE\",\"expectedCount\":1,\"actualCount\":1,\"parentExecutionId\":\""
                        + UUID.randomUUID()
                        + "\",\"symbols\":[{\"code\":\"AAA\",\"exchange\":\"HOSE\"}],\"detectedAt\":\"2035-01-02T12:00:00Z\"}";
        assertThatThrownBy(() -> consumer.handleSymbolUpsert(record(payload)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process symbol-upsert");
        verify(jobs).markExecutionFailed(eq(child), eq("write failed"), any(), any());
        verify(jobs).aggregateParentExecution(any());
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void persistedUpsertsNeverTreatMissingSymbolsAsDelistingEvidence() {
        var symbols = mock(SymbolRepository.class);
        var jobs = mock(JobService.class);
        var consumer =
                new SymbolUpsertConsumer(
                        mock(ApplicationEventPublisher.class), symbols, jobs, new JsonMapper());
        for (int expected : new int[] {1, 336}) {
            String payload =
                    "{\"exchange\":\"HOSE\",\"expectedCount\":"
                            + expected
                            + ",\"actualCount\":1,\"symbols\":[{\"code\":\"MBB\",\"exchange\":\"HOSE\"}]}";
            consumer.handleSymbolUpsert(record(payload));
        }
        verify(symbols, times(2)).upsertOne(eq("MBB"), eq("HOSE"), anyString());
        verifyNoMoreInteractions(symbols);
        verifyNoInteractions(jobs);
    }

    @Test
    void successfulProjectionPreservesPreparedMembershipAndLegacyChildCompletion() {
        for (boolean prepared : new boolean[] {true, false}) {
            var symbols = mock(SymbolRepository.class);
            var jobs = mock(JobService.class);
            var parent = UUID.randomUUID();
            var child = new JobExecutionHistory();
            when(jobs.isRunPreparedExecution(parent)).thenReturn(prepared);
            when(jobs.createChildExecution(any(), any(), any(), any())).thenReturn(child);
            var consumer =
                    new SymbolUpsertConsumer(
                            mock(ApplicationEventPublisher.class), symbols, jobs, new JsonMapper());
            consumer.handleSymbolUpsert(
                    record(
                            "{\"exchange\":\"HOSE\",\"expectedCount\":336,\"actualCount\":1,\"parentExecutionId\":\""
                                    + parent
                                    + "\",\"detectedAt\":\"2035-01-02T12:00:00Z\",\"symbols\":[{\"code\":\"MBB\",\"exchange\":\"HOSE\",\"meta\":{\"sector\":\"BANKS\"}}]}"));
            verify(symbols).upsertOne(eq("MBB"), eq("HOSE"), anyString());
            verifyNoMoreInteractions(symbols);
            verify(jobs).aggregateParentExecution(parent);
            if (prepared) {
                verify(jobs, never()).createChildExecution(any(), any(), any(), any());
                verify(jobs, never()).markExecutionSuccess(any(), anyInt(), anyInt(), any(), any());
            } else {
                verify(jobs).markExecutionSuccess(eq(child), eq(1), eq(0), any(), any());
            }
        }
    }

    private static ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("test-symbol-upsert", 0, 0L, "HOSE", payload);
    }
}
