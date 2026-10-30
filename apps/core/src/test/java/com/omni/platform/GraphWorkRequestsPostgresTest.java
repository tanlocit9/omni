package com.omni.platform;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunPreparationService;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import com.omni.platform.modules.scheduler.work.DefinitionConsolidationService;
import tools.jackson.databind.json.JsonMapper;

/** Real graph preparation, atomic acquire, barriers and retained history. Source-only in this handoff. */
@Testcontainers
@Timeout(180)
class GraphWorkRequestsPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Instant NOW=Instant.parse("2035-01-02T12:00:00Z");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JobRunPreparationService preparation;
    static GraphWorkRequestService graph;
    static SchedulerClaimService claims;
    static JobDefinitionRepository definitions;
    @BeforeAll static void start() {
        context=PlatformStartupPostgresTest.startApplication(POSTGRES,true,ctx->ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("graph-work-test",Map.of("app.scheduler.job-run.graph-expansion-enabled",true))));
        jdbc=context.getBean(JdbcTemplate.class);preparation=context.getBean(JobRunPreparationService.class);
        graph=context.getBean(GraphWorkRequestService.class);claims=context.getBean(SchedulerClaimService.class);
        definitions=context.getBean(JobDefinitionRepository.class);
    }
    @AfterAll static void stop() { if(context!=null)context.close(); }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE job_runs,job_execution_histories,symbols,atomic_work_slots,job_definition_consolidations CASCADE");
        jdbc.update("UPDATE job_definitions SET is_active=true,schedule_enabled=true,canonical_definition_id=NULL,"
                + "claim_token=NULL,claimed_by=NULL,claimed_at=NULL,claim_until=NULL,next_run='2035-02-01'");
        var json=context.getBean("jsonMapper",JsonMapper.class);
        for(var seed:JobDefinitionConfig.JOB_DEFINITION_SEEDS)jdbc.update("UPDATE job_definitions SET config_json=?::jsonb,fallback_sources=?::jsonb WHERE source=? AND job_type=? AND cron_expr=?",
                json.writeValueAsString(seed.config()),json.writeValueAsString(seed.fallbackSources()),seed.source().name(),seed.jobType().name(),seed.cronExpr());
        String sector=JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst();
        jdbc.update("INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('AAA','HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",sector);
    }
    JobDefinition target(JobType type) { return definitions.findAll().stream().filter(d->d.getJobType()==type).findFirst().orElseThrow(); }
    SchedulerClaim claim(JobDefinition definition) { return claims.claimJobDefinition(definition.getId(),NOW,"operator").orElseThrow(); }
    UUID prepare(JobDefinition target,String key) {
        return preparation.prepareManual(target,claim(target),NOW,"operator",key,"I9 regression",
                Map.of("scope",Map.of("type","SYMBOL","key","HOSE-AAA")),Map.of());
    }
    UUID request(UUID execution) { return jdbc.queryForObject("SELECT id FROM atomic_work_requests WHERE execution_id=?",UUID.class,execution); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }

    @Test void overlappingRequestsShareIndependentAncestorRunsAndCancellationKeepsOtherOwner() {
        var indicator=target(JobType.SYNC_INDICATORS);
        UUID first=prepare(indicator,"first"),second=prepare(indicator,"second");
        assertThat(second).isNotEqualTo(first);
        assertThat(count("atomic_work_attempts")).isEqualTo(3); // catalog, stock, indicator
        assertThat(count("job_runs")).isEqualTo(3);
        assertThat(count("atomic_work_request_members")).isEqualTo(6);
        assertThat(graph.cancel(request(first),"wrong-owner",NOW)).isFalse();
        assertThat(graph.cancel(request(first),"operator",NOW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atomic_work_attempts a WHERE atomic_work_admissible(a.execution_id)",Integer.class)).isEqualTo(1);
        assertThat(graph.inspect(request(second)).get("members")).asList().hasSize(3);
        assertThat(prepare(indicator,"second")).isEqualTo(second);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThat(graph.cancel(request(second),"operator",NOW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atomic_work_attempts a WHERE atomic_work_admissible(a.execution_id)",Integer.class)).isZero();
        prepare(indicator,"reattach");
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThatThrownBy(()->jdbc.update("UPDATE atomic_work_request_members SET is_target=false WHERE request_id=?",request(second)))
                .hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE atomic_work_attempts SET upstream_attempt_ids='[]'"))
                .hasMessageContaining("immutable");
    }

    @Test void concurrentDifferentTargetsAcquireCommonAncestorsOnlyOnce() throws Exception {
        var indicator=target(JobType.SYNC_INDICATORS);var features=target(JobType.PRECOMPUTE_SYMBOL_FEATURES);
        var firstLease=claim(indicator);var secondLease=claim(features);
        var expansion=Map.<String,Object>of("scope",Map.of("type","SYMBOL","key","HOSE-AAA"));
        var first=CompletableFuture.supplyAsync(()->preparation.prepareManual(indicator,firstLease,NOW,"operator","concurrent-a","I9",expansion,Map.of()));
        var second=CompletableFuture.supplyAsync(()->preparation.prepareManual(features,secondLease,NOW,"operator","concurrent-b","I9",expansion,Map.of()));
        CompletableFuture.allOf(first,second).get(90,TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                + "JOIN job_definitions d ON d.id=e.job_id WHERE d.job_type='SYNC_STOCK_PRICE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max(n) FROM (SELECT count(*) n FROM atomic_work_attempts GROUP BY work_id) x",Integer.class)).isEqualTo(1);
    }

    @Test void barrierIsRecheckedAtClaimAndUpstreamFailurePropagatesWithoutChangingOldAttempts() {
        UUID execution=prepare(target(JobType.SYNC_INDICATORS),"failure");
        UUID downstream=jdbc.queryForObject("SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? AND m.is_target",UUID.class,request(execution));
        UUID message=jdbc.queryForObject("SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",UUID.class,downstream);
        assertThat(context.getBean(SchedulerOutboxService.class).claimEligible(message,NOW,"test",Duration.ofMinutes(2),Map.of())).isNull();
        jdbc.update("UPDATE job_execution_histories SET status='FAILED',finished_at=? WHERE id IN "
                + "(SELECT a.execution_id FROM atomic_work_attempts a WHERE a.upstream_attempt_ids='[]'::jsonb)",java.sql.Timestamp.from(NOW));
        for(int i=0;i<8;i++)graph.reconcile();
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE execution_id=?",String.class,execution)).isEqualTo("FAILED");
        int old=count("atomic_work_attempts");
        var target=target(JobType.SYNC_INDICATORS);
        graph.retry(request(execution),"operator","retry-new-key",claim(target),NOW);
        assertThat(count("atomic_work_attempts")).isGreaterThan(old);
        assertThat(prepare(target,"failure")).isEqualTo(execution);
    }

    @Test void completionAndFreshRequestDoNotMistakeSuccessForAnInfiniteFreshnessCache() {
        UUID first=prepare(target(JobType.SYNC_INDICATORS),"done");
        jdbc.update("UPDATE job_execution_histories SET status='SUCCESS',finished_at=? WHERE id IN (SELECT execution_id FROM atomic_work_attempts)",java.sql.Timestamp.from(NOW));
        for(int i=0;i<3;i++)graph.reconcile();
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE execution_id=?",String.class,first)).isEqualTo("SUCCESS");
        prepare(target(JobType.SYNC_INDICATORS),"refresh");
        assertThat(count("atomic_work_attempts")).isEqualTo(6);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(6);
    }

    @Test void failedExpansionRollsBackAllDurableIntentAndPreservesClaimForRecovery() {
        var target=target(JobType.SYNC_INDICATORS);var lease=claim(target);
        assertThatThrownBy(()->preparation.prepareManual(target,lease,NOW,"operator","invalid","I9",
                Map.of("dateFrom","2035-02-01","dateTo","2035-02-01"),Map.of())).hasMessageContaining("Invalid");
        assertThat(count("atomic_work_requests")).isZero();assertThat(count("atomic_work_attempts")).isZero();
        assertThat(count("job_runs")).isZero();assertThat(count("scheduler_outbox_messages")).isZero();
    }

    @Test void consolidationPreservesHistoricalOffsetsAndRollsBackWithoutRewindingCadence() {
        var service=context.getBean(DefinitionConsolidationService.class);
        var stock=target(JobType.SYNC_STOCK_PRICE);
        UUID history=jdbc.queryForObject("INSERT INTO job_execution_histories(job_id,used_source,status,meta_json,new_offset,finished_at) "
                + "VALUES (?,'VND','SUCCESS','{\"workType\":\"SYMBOL\",\"workKey\":\"HOSE-AAA\"}','2035-01-01',now()) RETURNING id",UUID.class,stock.getId());
        var applied=service.consolidate(NOW);assertThat(applied).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_definitions WHERE NOT schedule_enabled",Integer.class)).isPositive();
        UUID retained=jdbc.queryForObject("SELECT retained_definition_id FROM job_definition_history_aliases WHERE definition_id=?",UUID.class,stock.getId());
        assertThat(context.getBean(JobExecutionHistoryRepository.class).findLastOffset(retained,"HOSE-AAA")).contains("2035-01-01");
        String before=jdbc.queryForObject("SELECT job_id::text FROM job_execution_histories WHERE id=?",String.class,history);
        for(UUID id:applied)assertThat(service.rollback(id,NOW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT job_id::text FROM job_execution_histories WHERE id=?",String.class,history)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_definitions WHERE canonical_definition_id IS NOT NULL",Integer.class)).isZero();
    }

    @Test void expiredTokenCanPersistWaitingAndUncoverReadyRows() {
        prepare(target(JobType.SYNC_INDICATORS),"lease");
        UUID message=jdbc.queryForObject("SELECT o.id FROM scheduler_outbox_messages o JOIN atomic_work_attempts a ON a.execution_id=o.execution_id WHERE a.upstream_attempt_ids='[]'::jsonb",UUID.class);
        jdbc.update("UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='dead',claim_until=now()-interval '1 minute' WHERE id=?",UUID.randomUUID(),message);
        assertThat(context.getBean(SchedulerOutboxService.class).markWaiting(message,NOW.plusSeconds(30),"WAITING_DATA")).isTrue();
        assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND dependency_reason='WAITING_DATA' FROM scheduler_outbox_messages WHERE id=?",Boolean.class,message)).isTrue();
        var metadata=target(JobType.SYNC_METADATA);
        UUID root=preparation.prepareManual(metadata,claim(metadata),NOW,"operator","ready-behind-waiting","I9",Map.of(),Map.of());
        UUID ready=jdbc.queryForObject("SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? AND m.is_target",UUID.class,request(root));
        assertThat(context.getBean(SchedulerOutboxService.class).findCandidates(NOW,1))
                .singleElement().satisfies(candidate->assertThat(candidate.executionId()).isEqualTo(ready));

    }
    @Test void expiredTokenCanPersistBlockedAndPropagateItsFailure() {
        UUID root=prepare(target(JobType.SYNC_INDICATORS),"blocked-lease");
        UUID message=jdbc.queryForObject("SELECT o.id FROM scheduler_outbox_messages o JOIN atomic_work_attempts a ON a.execution_id=o.execution_id WHERE a.upstream_attempt_ids='[]'::jsonb",UUID.class);
        jdbc.update("UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='dead',claim_until=now()-interval '1 minute' WHERE id=?",UUID.randomUUID(),message);
        assertThat(context.getBean(SchedulerOutboxService.class).markBlocked(message,NOW,"NO_PROVIDER")).isTrue();
        for(int i=0;i<8;i++)graph.reconcile();
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE execution_id=?",String.class,root)).isEqualTo("FAILED");
    }

    @Test void rolledBackConsolidationRejectsNewerOperatorConfig() {
        var service=context.getBean(DefinitionConsolidationService.class);
        UUID change=service.consolidate(NOW).getFirst();
        UUID retained=jdbc.queryForObject("SELECT retained_definition_id FROM job_definition_consolidations WHERE id=?",UUID.class,change);
        jdbc.update("UPDATE job_definitions SET config_json=config_json || '{\"sectorLevel\":1}'::jsonb WHERE id=?",retained);
        assertThatThrownBy(()->service.rollback(change,NOW)).hasMessageContaining("conflict");
        assertThat(jdbc.queryForObject("SELECT state FROM job_definition_consolidations WHERE id=?",String.class,change)).isEqualTo("APPLIED");
    }

    @Test void requestKeyCannotSilentlyChangeAcceptedParameters() {
        var target=target(JobType.SYNC_INDICATORS);
        prepare(target,"fixed-key");
        var lease=claim(target);
        assertThatThrownBy(()->preparation.prepareManual(target,lease,NOW,"operator","fixed-key","I9",
                Map.of("scope",Map.of("type","SYMBOL","key","HOSE-AAA"),"parameters",Map.of("indicatorSource","close")),Map.of()))
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
    }

    @Test void restartReplaysStoredMembershipWithoutAnotherExpansion() {
        var target=target(JobType.SYNC_INDICATORS);
        UUID original=prepare(target,"restart-key");UUID request=request(original);
        stop();start();
        assertThat(graph.inspect(request).get("members")).asList().hasSize(3);
        assertThat(prepare(target(JobType.SYNC_INDICATORS),"restart-key")).isEqualTo(original);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
    }

    @Test void workDiagnosticsRetainTheExistingAuthenticationBoundary() throws Exception {
        int port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:"+port+"/api/v1/jobs/atomic-work/consolidations")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isIn(401,403);
        }
    }

    @Test void graphSchedulerClaimsAllUncoveredSeedStrategies() {
        jdbc.update("UPDATE job_definitions SET next_run=?", java.sql.Timestamp.from(NOW));
        var selected = claims.claimDueJobs(NOW);
        assertThat(selected).hasSize(7);
        var signals = selected.stream().map(lease -> definitions.findById(lease.jobDefinitionId()).orElseThrow())
                .filter(definition -> definition.getJobType()==JobType.SYNC_SIGNALS)
                .map(definition -> definition.getConfigJson().get("strategy")).toList();
        assertThat(signals).containsExactlyInAnyOrder("ICHIMOKU_V1", "CONFIRMED_TREND_EQUALS");
    }

}
