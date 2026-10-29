package com.omni.platform.modules.scheduler.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import tools.jackson.databind.json.JsonMapper;

/** Read-only diagnostics under the existing authenticated /api/v1/jobs boundary. */
@RestController
@RequestMapping("/api/v1/jobs/atomic-work")
public class GraphWorkOperationsController {
    private final GraphWorkRequestService requests;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public GraphWorkOperationsController(GraphWorkRequestService requests, JdbcTemplate jdbc, JsonMapper json) {
        this.requests=requests; this.jdbc=jdbc; this.json=json;
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
