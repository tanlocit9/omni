package com.omni.platform.modules.scheduler.controllers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import tools.jackson.databind.json.JsonMapper;

/** Bounded Indicators/Signals entry point; generic manual trigger routes remain retired. */
@RestController
@RequestMapping("/api/v1/jobs/backfills")
public class IndicatorBackfillController {
    private static final List<JobType> SUPPORTED_GOALS =
            List.of(JobType.SYNC_INDICATORS, JobType.SYNC_SIGNALS);

    public record Goal(String goal, List<Target> targets) {}

    public record Target(UUID targetDefinitionId, String title) {}

    public record Backfill(
            String goal,
            List<String> sectorCodes,
            Integer sectorLevel,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean force,
            Boolean rerun,
            UUID targetDefinitionId,
            String symbolKey) {
        public Backfill(
                String goal,
                List<String> sectorCodes,
                Integer sectorLevel,
                LocalDate dateFrom,
                LocalDate dateTo,
                Boolean force,
                Boolean rerun,
                UUID targetDefinitionId) {
            this(
                    goal,
                    sectorCodes,
                    sectorLevel,
                    dateFrom,
                    dateTo,
                    force,
                    rerun,
                    targetDefinitionId,
                    null);
        }

        public Backfill(
                String goal,
                List<String> sectorCodes,
                Integer sectorLevel,
                LocalDate dateFrom,
                LocalDate dateTo,
                Boolean force,
                Boolean rerun) {
            this(goal, sectorCodes, sectorLevel, dateFrom, dateTo, force, rerun, null);
        }
    }

    private final GraphWorkRequestService graph;
    private final JobDefinitionRepository definitions;
    private final JsonMapper json;

    public IndicatorBackfillController(
            GraphWorkRequestService graph, JobDefinitionRepository definitions, JsonMapper json) {
        this.graph = graph;
        this.definitions = definitions;
        this.json = json;
    }

    @GetMapping("/goals")
    public List<Goal> goals() {
        var eligible = eligibleDefinitions();
        return SUPPORTED_GOALS.stream()
                .map(
                        goal ->
                                new Goal(
                                        goal.name(),
                                        eligible.stream()
                                                .filter(d -> d.getJobType() == goal)
                                                .map(d -> new Target(d.getId(), d.getTitle()))
                                                .toList()))
                .filter(goal -> !goal.targets().isEmpty())
                .toList();
    }

    private List<JobDefinition> eligibleDefinitions() {
        return definitions.findAll().stream()
                .filter(d -> SUPPORTED_GOALS.contains(d.getJobType()))
                .filter(d -> Boolean.TRUE.equals(d.getIsActive()))
                .filter(d -> d.getCanonicalDefinitionId() == null)
                .sorted(Comparator.comparing(JobDefinition::getId))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(
            @RequestHeader("X-Omni-User") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Backfill input) {
        if (input == null
                || SUPPORTED_GOALS.stream().noneMatch(goal -> goal.name().equals(input.goal()))
                || input.dateFrom() == null
                || input.dateTo() == null
                || input.sectorLevel() == null
                || input.sectorLevel() < 1
                || input.sectorLevel() > 4
                || input.sectorCodes() == null
                || input.sectorCodes().isEmpty()
                || input.sectorCodes().size() > 100
                || input.sectorCodes().stream()
                        .anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,64}"))
                || (input.symbolKey() != null
                        && !input.symbolKey().matches("[A-Za-z0-9_]{1,16}-[A-Za-z0-9_]{1,32}")))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid Indicators/Signals backfill scope");
        var targets =
                eligibleDefinitions().stream()
                        .filter(d -> d.getJobType() == JobType.valueOf(input.goal()))
                        .filter(
                                d ->
                                        input.targetDefinitionId() == null
                                                || input.targetDefinitionId().equals(d.getId()))
                        .toList();
        if (targets.size() != 1)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Select exactly one active canonical definition for the goal using targetDefinitionId");
        Map<String, Object> expansion = new LinkedHashMap<>();
        expansion.put("dateFrom", input.dateFrom().toString());
        expansion.put("dateTo", input.dateTo().toString());
        expansion.put(
                "sectorCodes",
                input.sectorCodes().stream()
                        .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                        .distinct()
                        .sorted()
                        .toList());
        expansion.put("sectorLevel", input.sectorLevel());
        if (input.symbolKey() != null)
            expansion.put(
                    "scope",
                    Map.of(
                            "type",
                            "SYMBOL",
                            "key",
                            input.symbolKey().toUpperCase(java.util.Locale.ROOT)));
        boolean force = Boolean.TRUE.equals(input.force());
        boolean rerun = force || Boolean.TRUE.equals(input.rerun());
        // Execution/data policy is request identity, not analytical work identity.
        expansion.put("parameters", Map.of());
        expansion.put("force", force);
        expansion.put("rerun", rerun);
        UUID execution;
        try {
            execution =
                    graph.prepareBackfill(
                            new Request(
                                    Mode.BACKFILL,
                                    actor,
                                    key,
                                    input.goal()
                                            + " sector/date backfill; rerun="
                                            + rerun
                                            + "; force="
                                            + force,
                                    null,
                                    json.writeValueAsString(expansion)),
                            targets.getFirst().getId(),
                            Instant.now(),
                            Map.of("force", force, "rerun", rerun));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    invalid.getMessage().contains("IDEMPOTENCY")
                            ? HttpStatus.CONFLICT
                            : HttpStatus.BAD_REQUEST,
                    invalid.getMessage(),
                    invalid);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
        return Map.of(
                "requestId",
                graph.requestId(execution),
                "executionId",
                execution,
                "status",
                "ACCEPTED",
                "goal",
                input.goal(),
                "force",
                force,
                "rerun",
                rerun,
                "membershipPolicy",
                "CURRENT_ACTIVE_AT_ACCEPTANCE");
    }
}
