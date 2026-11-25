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

/** Bounded snapshot/backfill goals; generic manual trigger routes remain retired. */
@RestController
@RequestMapping("/api/v1/jobs/backfills")
public class IndicatorBackfillController {
    private static final List<JobType> SUPPORTED_GOALS =
            List.of(JobType.SYNC_INDICATORS, JobType.SYNC_SIGNALS, JobType.SYNC_SYMBOLS);

    public record Goal(String goal, List<Target> targets) {}

    public record Target(UUID targetDefinitionId, String title) {}

    public record SectorOption(String code, String name) {}

    public record Backfill(
            String goal,
            List<String> sectorCodes,
            Integer sectorLevel,
            LocalDate dateFrom,
            LocalDate dateTo,
            String dataPolicy,
            UUID targetDefinitionId,
            String symbolKey,
            String exchange) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unsupported request field: " + name + "; use dataPolicy");
        }
    }

    private final GraphWorkRequestService graph;
    private final JobDefinitionRepository definitions;
    private final JsonMapper json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public IndicatorBackfillController(
            GraphWorkRequestService graph,
            JobDefinitionRepository definitions,
            JsonMapper json,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.graph = graph;
        this.definitions = definitions;
        this.json = json;
        this.jdbc = jdbc;
    }

    @GetMapping("/sectors")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<SectorOption> sectors(
            @RequestParam(name = "sectorLevel", defaultValue = "2") int level) {
        if (level < 1 || level > 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sector level");
        // Use the same active-symbol membership and JSON key as backfill admission.
        // Names are optional catalog enrichment, never a replacement for the actual code.
        return jdbc
                .queryForList(
                        """
                SELECT codes.code, coalesce(nullif(btrim(s.name_en),''),nullif(btrim(s.name_vi),''),codes.code) AS name
                FROM (SELECT DISTINCT meta_json->>CAST(? AS text) AS code
                      FROM symbols WHERE is_active=true) codes
                LEFT JOIN sectors s ON s.code=codes.code AND s.taxonomy=?
                      AND s.taxonomy_level=? AND s.is_active=true
                WHERE codes.code ~ '^[A-Z0-9_-]{1,64}$'
                ORDER BY codes.code
                """,
                        "sectorLv" + level + "Code",
                        com.omni.platform.modules.scheduler.constants.SectorSeedConfig
                                .DEFAULT_TAXONOMY,
                        level)
                .stream()
                .map(row -> new SectorOption((String) row.get("code"), (String) row.get("name")))
                .toList();
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
                || SUPPORTED_GOALS.stream().noneMatch(goal -> goal.name().equals(input.goal())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported goal");
        String dataPolicy = input.dataPolicy() == null ? "PREFER_EXISTING" : input.dataPolicy();
        if (!List.of("PREFER_EXISTING", "REFRESH").contains(dataPolicy))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dataPolicy");
        boolean symbols = JobType.SYNC_SYMBOLS.name().equals(input.goal());
        Instant acceptedAt = Instant.now();
        var today =
                acceptedAt
                        .atZone(com.omni.platform.modules.scheduler.expansion.WorkExpansion.ZONE)
                        .toLocalDate();
        var from = symbols && input.dateFrom() == null ? today : input.dateFrom();
        var to = symbols && input.dateTo() == null ? today : input.dateTo();
        if (symbols) {
            if (input.exchange() == null
                    || !List.of("HOSE", "HNX", "UPCOM")
                            .contains(input.exchange().toUpperCase(java.util.Locale.ROOT))
                    || input.sectorCodes() != null
                    || input.sectorLevel() != null
                    || input.symbolKey() != null
                    || !today.equals(from)
                    || !today.equals(to))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Symbols requires one exchange and today's snapshot only");
        } else if (from == null
                || to == null
                || input.exchange() != null
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
        expansion.put("dateFrom", from.toString());
        expansion.put("dateTo", to.toString());
        if (symbols) {
            expansion.put(
                    "scope",
                    Map.of(
                            "type",
                            "EXCHANGE",
                            "key",
                            input.exchange().toUpperCase(java.util.Locale.ROOT)));
        } else {
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
        }
        // Execution/data policy is request identity, not analytical work identity.
        expansion.put("parameters", Map.of());
        expansion.put("dataPolicy", dataPolicy);
        UUID execution;
        try {
            execution =
                    graph.prepareBackfill(
                            new Request(
                                    Mode.BACKFILL,
                                    actor,
                                    key,
                                    input.goal()
                                            + (symbols
                                                    ? " current exchange snapshot; "
                                                    : " sector/date backfill; ")
                                            + "dataPolicy="
                                            + dataPolicy,
                                    null,
                                    json.writeValueAsString(expansion)),
                            targets.getFirst().getId(),
                            acceptedAt,
                            Map.of("dataPolicy", dataPolicy));
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
                "dataPolicy",
                dataPolicy,
                "membershipPolicy",
                "CURRENT_ACTIVE_AT_ACCEPTANCE");
    }
}
