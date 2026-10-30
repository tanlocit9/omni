package com.omni.platform.modules.scheduler.work;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.services.JobOperationException;
import tools.jackson.databind.json.JsonMapper;

/** Authenticated, bounded observation only. Never evaluates READY or mutates work. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
public class AtomicWorkDiagnosticsService {
    private final JdbcTemplate template;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final String snapshotSql;
    private final String unfinishedSql;
    private final String workSql;

    public AtomicWorkDiagnosticsService(DataSource dataSource, JsonMapper json) throws IOException {
        this.template = new JdbcTemplate(dataSource);
        this.template.setQueryTimeout(5);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.json = json;
        this.snapshotSql = sql("snapshot.sql");
        this.unfinishedSql = sql("published_unfinished.sql");
        this.workSql = sql("inspect_work.sql");
    }

    public Map<String, Object> snapshot(Instant since, Instant until, Instant observedAt,
                                      UUID afterId, int limit, int hours) {
        Instant now = Instant.now();
        Instant observed = observation(observedAt, now);
        if ((since == null) != (until == null)) invalid("Both since and until are required together.");
        if (hours < 1 || hours > 168) invalid("Window hours must be between 1 and 168.");
        Instant end = until == null ? observed : until;
        Instant start = since == null ? end.minus(Duration.ofHours(hours)) : since;
        if (!start.isBefore(end) || end.isAfter(observed)
                || Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0)
            invalid("Use a positive window of at most seven days ending no later than observedAt.");
        bound(limit, 100);
        return query(snapshotSql, Map.of("since", start.toString(), "until", end.toString(),
                "observed_at", observed.toString(), "after_id", cursor(afterId), "row_limit", limit));
    }

    public Map<String, Object> unfinished(Instant observedAt, UUID afterId, int limit) {
        Instant observed = observation(observedAt, Instant.now());
        bound(limit, 100);
        return query(unfinishedSql, Map.of("observed_at", observed.toString(),
                "published_before", observed.minus(Duration.ofMinutes(30)).toString(),
                "after_id", cursor(afterId), "row_limit", limit));
    }

    public Map<String, Object> work(String atomicWorkId) {
        if (atomicWorkId == null || !atomicWorkId.matches("work:[0-9a-f]{64}"))
            invalid("atomicWorkId must be work: followed by 64 lowercase hexadecimal characters.");
        return query(workSql, Map.of("atomic_work_id", atomicWorkId,
                "observed_at", Instant.now().toString()));
    }

    private static Instant observation(Instant value, Instant now) {
        Instant observed = value == null ? now : value;
        if (observed.isAfter(now)) invalid("observedAt cannot be in the future.");
        return observed;
    }

    private static void bound(int value, int max) {
        if (value < 1 || value > max) invalid("Page size must be between 1 and " + max + ".");
    }

    private static String cursor(UUID id) { return id == null ? "" : id.toString(); }

    private static void invalid(String message) {
        throw new JobOperationException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSTIC_WINDOW", message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> query(String sql, Map<String, Object> parameters) {
        try {
            template.execute("SET LOCAL lock_timeout='500ms'");
            return json.readValue(jdbc.queryForObject(sql, parameters, String.class), Map.class);
        } catch (DataAccessException unavailable) {
            throw new JobOperationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ATOMIC_DIAGNOSTICS_UNAVAILABLE", "Work diagnostics are unavailable; check schema and database health.");
        }
    }

    private static String sql(String name) throws IOException {
        return new ClassPathResource("diagnostics/p14/" + name)
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(":'([a-z_]+)'", ":$1");
    }
}
