package com.omni.platform.modules.scheduler.work;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.shared.executions.WorkType;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** Consolidates presets in the existing catalog. Historical rows remain addressable and executable manually. */
@Service
public class DefinitionConsolidationService {
    private final JdbcTemplate jdbc;
    private final JobDefinitionRepository definitions;
    private final EntityManager entities;
    private final JsonMapper json;
    private final boolean graphEnabled;

    public DefinitionConsolidationService(JdbcTemplate jdbc, JobDefinitionRepository definitions,
            EntityManager entities, JsonMapper json,
            @Value("${app.scheduler.job-run.graph-expansion-enabled:false}") boolean graphEnabled) {
        this.jdbc=jdbc; this.definitions=definitions; this.entities=entities; this.json=json;
        this.graphEnabled=graphEnabled;
    }

    public record Group(UUID retained, List<UUID> definitions, Map<String,Object> configuration) {
        public Group { definitions=List.copyOf(definitions); configuration=WorkExpansion.freeze(configuration); }
    }

    /** Pure inventory: source/fallback/strategy/readiness/default differences never merge. */
    public static List<Group> inventory(List<JobDefinition> catalog) {
        Map<String,List<JobDefinition>> groups = new TreeMap<>();
        for (var definition : catalog) {
            if (!Boolean.TRUE.equals(definition.getIsActive()) || !Boolean.TRUE.equals(definition.getScheduleEnabled())
                    || definition.getCanonicalDefinitionId()!=null
                    || definition.getCronExpr()==null || definition.getCronExpr().isBlank()) continue;
            Map<String,Object> config = new LinkedHashMap<>(definition.getConfigJson()==null ? Map.of() : definition.getConfigJson());
            if (mergeSectorSelectors(definition.getJobType())) config.remove("sectorCodes");
            String signature = WorkExpansion.canonical(List.of(definition.getSource().name(), definition.getJobType().name(),
                    definition.getFallbackSources()==null ? List.of() : definition.getFallbackSources(), config));
            groups.computeIfAbsent(signature,k->new ArrayList<>()).add(definition);
        }
        List<Group> result = new ArrayList<>();
        for (var group : groups.values()) {
            if (group.size()<2) continue;
            group.sort(Comparator.comparing(JobDefinition::getCronExpr).thenComparing(d->d.getId().toString()));
            var first = group.getFirst();
            Map<String,Object> config = new LinkedHashMap<>(first.getConfigJson()==null ? Map.of() : first.getConfigJson());
            if (mergeSectorSelectors(first.getJobType())) {
                boolean allSectors = group.stream().anyMatch(d -> !(d.getConfigJson()!=null && d.getConfigJson().get("sectorCodes") instanceof List<?> codes) || codes.isEmpty());
                List<String> codes = allSectors ? List.of() : group.stream()
                        .flatMap(d->((List<?>)d.getConfigJson().get("sectorCodes")).stream())
                        .map(Object::toString).distinct().sorted().toList();
                config.put("sectorCodes",codes);
            }
            result.add(new Group(first.getId(),group.stream().map(JobDefinition::getId).toList(),config));
        }
        return List.copyOf(result);
    }

    private static boolean mergeSectorSelectors(JobType type) {
        return WorkExpansion.unit(type)==WorkType.SYMBOL || type==JobType.PRECOMPUTE_SECTOR_FEATURES;
    }

    /** Explicit internal operation; no migration/startup silently changes schedules. */
    @Transactional(timeout=60)
    public List<UUID> consolidate(Instant now) {
        if (!graphEnabled) throw new IllegalStateException("Consolidation requires graph expansion");
        jdbc.queryForList("SELECT id FROM job_definitions ORDER BY id FOR UPDATE");
        entities.clear();
        var catalog = definitions.findAll();
        var byId = catalog.stream().collect(java.util.stream.Collectors.toMap(JobDefinition::getId,d->d));
        List<UUID> applied = new ArrayList<>();
        for (var group : inventory(catalog)) {
            List<Map<String,Object>> inventory = new ArrayList<>();
            Instant earliest = null;
            boolean immediate = false;
            for (UUID id : group.definitions()) {
                var definition = byId.get(id);
                if (definition.getClaimUntil()!=null && definition.getClaimUntil().isAfter(now))
                    throw new IllegalStateException("Definition consolidation has an active lease");
                Map<String,Object> before = new LinkedHashMap<>();
                before.put("id",id.toString()); before.put("source",definition.getSource().name());
                before.put("jobType",definition.getJobType().name()); before.put("cron",definition.getCronExpr());
                before.put("config",definition.getConfigJson()); before.put("fallbackSources",definition.getFallbackSources());
                before.put("scheduleEnabled",definition.getScheduleEnabled());
                before.put("canonicalDefinitionId",null);
                before.put("nextRun",definition.getNextRun()==null ? null : definition.getNextRun().toString());
                inventory.add(before);
                if (definition.getNextRun()==null) immediate=true;
                else if (earliest==null || definition.getNextRun().isBefore(earliest)) earliest=definition.getNextRun();
            }
            UUID changeId=UUID.randomUUID();
            jdbc.update("INSERT INTO job_definition_consolidations(id,retained_definition_id,inventory_json,applied_config,created_at,state) "
                    + "VALUES (?,?,?::jsonb,?::jsonb,?,'APPLIED')",changeId,group.retained(),
                    json.writeValueAsString(inventory),json.writeValueAsString(group.configuration()),Timestamp.from(now));
            jdbc.update("UPDATE job_definitions SET config_json=?::jsonb,next_run=?,updated_at=? WHERE id=?",
                    json.writeValueAsString(group.configuration()),immediate ? null : Timestamp.from(earliest),Timestamp.from(now),group.retained());
            for (UUID id : group.definitions()) if (!id.equals(group.retained()))
                jdbc.update("UPDATE job_definitions SET schedule_enabled=false,canonical_definition_id=?,updated_at=? WHERE id=?",
                        group.retained(),Timestamp.from(now),id);
            applied.add(changeId);
        }
        entities.clear(); return List.copyOf(applied);
    }

    /** Refuse rollback over newer operator config, identity or lease edits; never rewind next_run. */
    @Transactional(timeout=60)
    @SuppressWarnings("unchecked")
    public boolean rollback(UUID changeId, Instant now) {
        var changes=jdbc.queryForList("SELECT retained_definition_id,inventory_json::text AS inventory,applied_config::text AS applied "
                + "FROM job_definition_consolidations WHERE id=? AND state='APPLIED' FOR UPDATE",changeId);
        if (changes.isEmpty()) return false;
        var change=changes.getFirst();
        List<Map<String,Object>> inventory=json.readValue(change.get("inventory").toString(),List.class);
        for (UUID id : inventory.stream().map(row->UUID.fromString(row.get("id").toString())).sorted().toList())
            jdbc.queryForObject("SELECT id FROM job_definitions WHERE id=? FOR UPDATE",UUID.class,id);
        entities.clear();
        var retained=definitions.findById((UUID)change.get("retained_definition_id")).orElseThrow();
        if (!WorkExpansion.canonical(retained.getConfigJson()).equals(WorkExpansion.canonical(json.readValue(change.get("applied").toString(),Map.class))))
            throw new IllegalStateException("Consolidation rollback config conflict");
        for (var before : inventory) {
            UUID id=UUID.fromString(before.get("id").toString());
            var current=definitions.findById(id).orElseThrow();
            boolean canonical=id.equals(retained.getId());
            if ((current.getClaimUntil()!=null && current.getClaimUntil().isAfter(now))
                    || !current.getCronExpr().equals(before.get("cron"))
                    || !current.getSource().name().equals(before.get("source"))
                    || !current.getJobType().name().equals(before.get("jobType"))
                    || !WorkExpansion.canonical(current.getFallbackSources()).equals(WorkExpansion.canonical(before.get("fallbackSources")))
                    || (canonical && (current.getCanonicalDefinitionId()!=null
                        || !current.getScheduleEnabled().equals(before.get("scheduleEnabled"))))
                    || (!canonical && !Boolean.FALSE.equals(current.getScheduleEnabled()))
                    || (!canonical && !retained.getId().equals(current.getCanonicalDefinitionId()))
                    || (!canonical && !WorkExpansion.canonical(current.getConfigJson()).equals(WorkExpansion.canonical(before.get("config")))))
                throw new IllegalStateException("Consolidation rollback identity/lease conflict");
            jdbc.update("UPDATE job_definitions SET config_json=?::jsonb,schedule_enabled=?,canonical_definition_id=NULL,updated_at=? WHERE id=?",
                    json.writeValueAsString(before.get("config")),before.get("scheduleEnabled"),Timestamp.from(now),id);
        }
        jdbc.update("UPDATE job_definition_consolidations SET state='ROLLED_BACK' WHERE id=?",changeId);
        entities.clear(); return true;
    }
}
