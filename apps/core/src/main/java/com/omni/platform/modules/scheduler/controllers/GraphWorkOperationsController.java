package com.omni.platform.modules.scheduler.controllers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import com.omni.platform.modules.scheduler.work.AtomicWorkDiagnosticsService;
import tools.jackson.databind.json.JsonMapper;

/** Read-only diagnostics under the existing authenticated /api/v1/jobs boundary. */
@RestController
@RequestMapping("/api/v1/jobs/atomic-work")
public class GraphWorkOperationsController {
    private final GraphWorkRequestService requests;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final AtomicWorkDiagnosticsService diagnostics;
    public GraphWorkOperationsController(GraphWorkRequestService requests, JdbcTemplate jdbc, JsonMapper json, AtomicWorkDiagnosticsService diagnostics) {
        this.requests=requests; this.jdbc=jdbc; this.json=json; this.diagnostics=diagnostics;
    }
    @GetMapping("/snapshot")
    public Map<String,Object> snapshot(
            @RequestParam(required=false) Instant since,
            @RequestParam(required=false) Instant until,
            @RequestParam(required=false) Instant observedAt,
            @RequestParam(required=false) UUID afterId,
            @RequestParam(defaultValue="25") int limit,
            @RequestParam(defaultValue="24") int hours) {
        return diagnostics.snapshot(since, until, observedAt, afterId, limit, hours);
    }

    @GetMapping("/published-unfinished")
    public Map<String,Object> unfinished(
            @RequestParam(required=false) Instant observedAt,
            @RequestParam(required=false) UUID afterId,
            @RequestParam(defaultValue="50") int limit) {
        return diagnostics.unfinished(observedAt, afterId, limit);
    }

    @GetMapping("/work")
    public Map<String,Object> work(@RequestParam String atomicWorkId) {
        return diagnostics.work(atomicWorkId);
    }

    @GetMapping("/requests/{id}")
    public Map<String,Object> request(@PathVariable UUID id) {
        try { return requests.inspect(id); }
        catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "Atomic request not found");
        }
    }

    @GetMapping("/consolidations")
    public List<Map<String,Object>> consolidations() {
        var rows=jdbc.queryForList("SELECT id,retained_definition_id,state,inventory_json::text AS inventory,created_at "
                + "FROM job_definition_consolidations ORDER BY created_at DESC,id LIMIT 100");
        for(var row:rows)row.put("inventory",json.readValue(row.get("inventory").toString(),List.class));
        return rows;
    }
}
