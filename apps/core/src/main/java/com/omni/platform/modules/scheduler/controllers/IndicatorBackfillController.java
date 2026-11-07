package com.omni.platform.modules.scheduler.controllers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import tools.jackson.databind.json.JsonMapper;

/** Bounded Indicators-only entry point; existing generic manual trigger routes remain retired. */
@RestController
@RequestMapping("/api/v1/jobs/backfills")
public class IndicatorBackfillController {
    public record Backfill(String goal, List<String> sectorCodes, Integer sectorLevel,
            LocalDate dateFrom, LocalDate dateTo, Boolean force) {}

    private final GraphWorkRequestService graph;
    private final JobDefinitionRepository definitions;
    private final JsonMapper json;

    public IndicatorBackfillController(GraphWorkRequestService graph,
            JobDefinitionRepository definitions, JsonMapper json) {
        this.graph = graph;
        this.definitions = definitions;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(@RequestHeader("X-Omni-User") String actor,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Backfill input) {
        if (input == null || !"SYNC_INDICATORS".equals(input.goal())
                || input.dateFrom() == null || input.dateTo() == null
                || input.sectorLevel() == null || input.sectorLevel() < 1 || input.sectorLevel() > 4
                || input.sectorCodes() == null || input.sectorCodes().isEmpty()
                || input.sectorCodes().size() > 100 || input.sectorCodes().stream()
                        .anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,64}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Indicators backfill scope");
        var targets = definitions.findAll().stream()
                .filter(d -> d.getJobType() == JobType.SYNC_INDICATORS && Boolean.TRUE.equals(d.getIsActive())
                        && d.getCanonicalDefinitionId() == null).toList();
        if (targets.size() != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exactly one active canonical Indicators definition is required");
        Map<String, Object> expansion = new LinkedHashMap<>();
        expansion.put("dateFrom", input.dateFrom().toString());
        expansion.put("dateTo", input.dateTo().toString());
        expansion.put("sectorCodes", input.sectorCodes().stream().map(s -> s.toUpperCase(java.util.Locale.ROOT)).distinct().sorted().toList());
        expansion.put("sectorLevel", input.sectorLevel());
        boolean force = Boolean.TRUE.equals(input.force());
        // Force is part of request idempotency, not the immutable analytical work identity.
        expansion.put("parameters", Map.of());
        expansion.put("force", force);
        UUID execution;
        try {
            execution = graph.prepareBackfill(new Request(Mode.BACKFILL, actor, key,
                    "Indicators sector/date backfill; force=" + force, null,
                    json.writeValueAsString(expansion)), targets.getFirst().getId(), Instant.now(),
                    Map.of("force", force));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(invalid.getMessage().contains("IDEMPOTENCY") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
        return Map.of("requestId", graph.requestId(execution), "executionId", execution, "status", "ACCEPTED", "goal", input.goal(),
                "force", force, "membershipPolicy", "CURRENT_ACTIVE_AT_ACCEPTANCE");
    }
}
