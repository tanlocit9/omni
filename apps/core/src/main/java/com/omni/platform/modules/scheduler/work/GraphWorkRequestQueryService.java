package com.omni.platform.modules.scheduler.work;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.services.JobOperationException;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, read-only request discovery for polling. No graph or attempt mutation. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
public class GraphWorkRequestQueryService {
    public record Cursor(Instant beforeCreatedAt, UUID beforeId) {}

    public record Summary(
            UUID requestId, UUID executionId, UUID targetDefinitionId, String goal,
            String mode, String state, Instant createdAt, Instant startedAt, Instant finishedAt,
            Long elapsedMs, long totalMembers, Map<String, Long> memberCounts,
            long unfinishedMembers, boolean isSettled, boolean hasFailure,
            List<Map<String, Object>> jobs, Integer recordsSynced, Integer recordsSkipped) {}

    public record Page(Instant observedAt, List<Summary> items, boolean hasMore, Cursor nextCursor) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public GraphWorkRequestQueryService(DataSource dataSource, JsonMapper json) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
        this.json = json;
    }

    public Page list(String status, int limit, Instant observedAt, Instant beforeCreatedAt, UUID beforeId) {
        String filter = status == null ? "active" : status.toLowerCase(Locale.ROOT);
        if (!List.of("active", "history", "all").contains(filter))
            invalid("status must be active, history or all");
        if (limit < 1 || limit > 100) invalid("limit must be between 1 and 100");
        if ((beforeCreatedAt == null) != (beforeId == null))
            invalid("beforeCreatedAt and beforeId are required together");
        Instant now = Instant.now();
        Instant observed = observedAt == null ? now : observedAt;
        if (observed.isAfter(now)) invalid("observedAt cannot be in the future");
        if (beforeCreatedAt != null && beforeCreatedAt.isAfter(observed))
            invalid("beforeCreatedAt cannot be later than observedAt");
        String states = switch (filter) {
            case "active" -> "r.state IN ('PREPARING','PREPARED')";
            case "history" -> "r.state IN ('SUCCESS','FAILED','CANCELLED')";
            default -> "true";
        };
        String cursor = beforeId == null ? "" : " AND (r.created_at,r.id)<(?,?)";
        String sql = """
                WITH page AS (
                    SELECT r.id,r.execution_id,r.target_definition_id,r.mode,r.state,r.created_at
                    FROM atomic_work_requests r WHERE %s AND r.created_at<=? %s
                    ORDER BY r.created_at DESC,r.id DESC LIMIT ?
                )
                SELECT p.*,d.job_type AS goal,e.started_at,e.finished_at,e.records_synced,e.records_skipped,
                    CASE WHEN e.started_at IS NOT NULL THEN floor(greatest(0,
                        extract(epoch FROM (coalesce(e.finished_at,?::timestamptz)-e.started_at))*1000))::bigint
                        END AS elapsed_ms,
                    coalesce(s.total,0) AS total,coalesce(s.unfinished,0) AS unfinished,
                    coalesce(s.has_failure,false) AS has_failure,
                    coalesce(s.counts,'{}'::jsonb)::text AS counts,
                    coalesce(s.jobs,'[]'::jsonb)::text AS jobs
                FROM page p JOIN job_execution_histories e ON e.id=p.execution_id
                JOIN job_definitions d ON d.id=p.target_definition_id
                LEFT JOIN LATERAL (
                    WITH members AS (
                        SELECT h.status,j.job_type FROM atomic_work_request_members m
                        JOIN atomic_work_attempts a ON a.id=m.attempt_id
                        JOIN job_execution_histories h ON h.id=a.execution_id
                        JOIN job_definitions j ON j.id=h.job_id WHERE m.request_id=p.id
                    ), grouped AS (
                        SELECT job_type,status,count(*) AS n FROM members GROUP BY job_type,status
                    ), job_counts AS (
                        SELECT job_type,sum(n) AS total,jsonb_object_agg(status,n) AS counts
                        FROM grouped GROUP BY job_type
                    ), status_counts AS (
                        SELECT status,sum(n) AS n FROM grouped GROUP BY status
                    )
                    SELECT (SELECT sum(n) FROM grouped) AS total,
                        (SELECT sum(n) FROM grouped WHERE status NOT IN ('SUCCESS','FAILED','ERROR','BLOCKED')) AS unfinished,
                        EXISTS(SELECT 1 FROM grouped WHERE status IN ('FAILED','ERROR','BLOCKED')) AS has_failure,
                        (SELECT jsonb_object_agg(status,n) FROM status_counts) AS counts,
                        (SELECT jsonb_agg(jsonb_build_object('jobType',job_type,'totalMembers',total,
                            'memberCounts',counts) ORDER BY job_type) FROM job_counts) AS jobs
                ) s ON true ORDER BY p.created_at DESC,p.id DESC
                """.formatted(states, cursor);
        var args = new java.util.ArrayList<Object>();
        args.add(Timestamp.from(observed));
        if (beforeId != null) {
            args.add(Timestamp.from(beforeCreatedAt));
            args.add(beforeId);
        }
        args.add(limit + 1);
        args.add(Timestamp.from(observed));
        try {
            jdbc.execute("SET LOCAL lock_timeout='500ms'");
            List<Summary> rows = jdbc.query(sql, (rs, index) -> summary(rs), args.toArray());
            boolean more = rows.size() > limit;
            List<Summary> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            Cursor next = more ? new Cursor(items.getLast().createdAt(), items.getLast().requestId()) : null;
            return new Page(observed, items, more, next);
        } catch (DataAccessException unavailable) {
            throw new JobOperationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ATOMIC_REQUESTS_UNAVAILABLE", "Request summaries are unavailable; check database health.");
        }
    }

    @SuppressWarnings("unchecked")
    private Summary summary(ResultSet row) throws SQLException {
        Map<String, Number> rawCounts = json.readValue(row.getString("counts"), Map.class);
        var counts = new java.util.TreeMap<String, Long>();
        rawCounts.forEach((key, value) -> counts.put(key, value.longValue()));
        long unfinished = row.getLong("unfinished");
        return new Summary(row.getObject("id", UUID.class), row.getObject("execution_id", UUID.class),
                row.getObject("target_definition_id", UUID.class), row.getString("goal"),
                row.getString("mode"), row.getString("state"), instant(row, "created_at"),
                instant(row, "started_at"), instant(row, "finished_at"), row.getObject("elapsed_ms", Long.class),
                row.getLong("total"), Map.copyOf(counts), unfinished, unfinished == 0,
                row.getBoolean("has_failure"), json.readValue(row.getString("jobs"), List.class),
                row.getObject("records_synced", Integer.class), row.getObject("records_skipped", Integer.class));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void invalid(String message) {
        throw new JobOperationException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PAGE", message);
    }
}
