package com.omni.platform.modules.scheduler.expansion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.JobMessage;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.shared.executions.WorkType;
import tools.jackson.databind.json.JsonMapper;

/** Preparation-only expansion. Publication/retry never calls this boundary. */
public final class WorkExpansion {
    public static final int MAX_WORK = 5000;
    public static final int MAX_RANGE_DAYS = 366;
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SELECTORS =
            Set.of(
                    "scope",
                    "dateFrom",
                    "dateTo",
                    "startDate",
                    "endDate",
                    "tradingDate",
                    "businessDate",
                    "evaluationDate",
                    "exchanges",
                    "sectorCodes",
                    "symbolKeys",
                    "parameters",
                    "dataDependencies",
                    "dependsOnJobs",
                    "dependsOnDatasets",
                    "producesDatasets",
                    "workExpansionRequest",
                    "_expansionScope");
    private static final Set<String> IDENTITY_FIELDS =
            Set.of(
                    "jobDefinitionId",
                    "executionId",
                    "parentExecutionId",
                    "workType",
                    "workKey",
                    "symbolKey",
                    "exchange",
                    "sectorCode",
                    "tradingDate",
                    "evaluationDate",
                    "fromOffset",
                    "toOffset",
                    "timestamp",
                    "metadata");

    private WorkExpansion() {}

    public record Plan(
            List<LocalDate> days, Map<String, Object> config, Map<String, Object> accepted) {
        public Plan {
            days = List.copyOf(days);
            config = freeze(config);
            accepted = freeze(accepted);
        }
    }

    public static Plan resolve(JobDefinition job, JobExecutionHistory parent, Instant acceptedAt) {
        Map<String, Object> config =
                new LinkedHashMap<>(job.getConfigJson() == null ? Map.of() : job.getConfigJson());
        Map<String, Object> request = new LinkedHashMap<>();
        for (String key : List.of("scope", "dateFrom", "dateTo", "parameters")) {
            if (config.containsKey(key)) request.put(key, config.get(key));
        }
        Map<String, Object> meta = parent.getMetaJson() == null ? Map.of() : parent.getMetaJson();
        if (meta.get("metadataTarget") instanceof Map<?, ?> oldTarget
                && job.getJobType() == JobType.SYNC_INTRADAY_EOD) {
            for (var pair :
                    Map.of("startDate", "dateFrom", "endDate", "dateTo", "tradingDate", "dateFrom")
                            .entrySet())
                if (oldTarget.get(pair.getKey()) != null)
                    request.put(pair.getValue(), oldTarget.get(pair.getKey()));
            if (oldTarget.get("tradingDate") != null)
                request.put("dateTo", oldTarget.get("tradingDate"));
        }
        if (meta.get("expansionRequest") instanceof Map<?, ?> supplied)
            supplied.forEach((k, v) -> request.put(String.valueOf(k), v));
        if (!Set.of("scope", "dateFrom", "dateTo", "parameters").containsAll(request.keySet()))
            throw new IllegalArgumentException("Unknown expansion request field");
        Object override = request.get("parameters");
        if (override != null && !(override instanceof Map<?, ?>))
            throw new IllegalArgumentException("Expansion parameters must be an object");
        if (override instanceof Map<?, ?> parameters) {
            parameters.forEach(
                    (k, v) -> {
                        String key = String.valueOf(k);
                        if (SELECTORS.contains(key))
                            throw new IllegalArgumentException(
                                    "Put selectors at request level: " + key);
                        config.put(key, v);
                    });
        }
        LocalDate today = acceptedAt.atZone(ZONE).toLocalDate();
        LocalDate from = date(request.get("dateFrom"), today);
        LocalDate to = date(request.get("dateTo"), today);
        if (from.isAfter(to)
                || to.isAfter(today)
                || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS)
            throw new IllegalArgumentException("Invalid/future/oversized expansion date range");
        if ((job.getJobType() == JobType.SYNC_SYMBOLS || job.getJobType() == JobType.SYNC_METADATA)
                && (!from.equals(today) || !to.equals(today)))
            throw new IllegalArgumentException("This job supports current snapshots only");
        Map<String, Object> scope = map(request.get("scope"));
        if (!scope.isEmpty()) {
            WorkType type = WorkType.valueOf(required(scope, "type").toUpperCase(Locale.ROOT));
            String key = required(scope, "key").toUpperCase(Locale.ROOT);
            WorkType unit = unit(job.getJobType());
            if (type != unit
                    && !(unit == WorkType.SYMBOL && type == WorkType.EXCHANGE)
                    && type != WorkType.GLOBAL)
                throw new IllegalArgumentException("Unsupported scope for " + job.getJobType());
            scope = Map.of("type", type.name(), "key", key);
            config.put("_expansionScope", scope);
            if (unit == WorkType.EXCHANGE && type == WorkType.EXCHANGE)
                config.put("exchanges", List.of(key));
            if (unit == WorkType.SECTOR && type == WorkType.SECTOR)
                config.put("sectorCodes", List.of(key));
        }
        resolveDefaults(job.getJobType(), config);
        config.put("source", config.getOrDefault("source", job.getSource().name()));
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY)
                days.add(day);
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("membershipPolicy", "CURRENT_ACTIVE_AT_ACCEPTANCE");
        accepted.put("dateFrom", from.toString());
        accepted.put("dateTo", to.toString());
        accepted.put("scope", scope);
        accepted.put("resolvedConfig", config);
        accepted.put("days", days.stream().map(LocalDate::toString).toList());
        return new Plan(days, config, accepted);
    }

    private static void resolveDefaults(JobType type, Map<String, Object> config) {
        switch (type) {
            case SYNC_INDICATORS -> {
                var c = JobConfigMapper.toIndicatorsConfig(config);
                config.put("timeframe", c.timeframe());
                config.put("indicatorSource", c.indicatorSource());
                config.put("indicators", c.indicators());
            }
            case SYNC_SIGNALS, EVALUATE_SIGNALS -> {
                var c = JobConfigMapper.toSignalsConfig(config);
                config.put("timeframe", c.timeframe());
                config.put("strategy", c.strategy());
            }
            case PRECOMPUTE_SYMBOL_FEATURES,
                    PRECOMPUTE_SECTOR_FEATURES,
                    SECTOR_ROTATION_BACKTEST -> {
                var c = JobConfigMapper.toSectorWaveConfig(config);
                config.put("timeframe", c.timeframe());
                config.put("strategy", c.strategy());
            }
            case SECTOR_TRANSITION_ANALYZE, SECTOR_TRANSITION_EVALUATE_OUTCOMES -> {
                var c = JobConfigMapper.toSectorTransitionConfig(config);
                config.put("timeframe", c.timeframe());
                config.put("strategy", c.strategy());
                config.put("predictionHorizons", c.predictionHorizons());
            }
            default -> {}
        }
    }

    public static WorkType unit(JobType type) {
        return switch (type) {
            case SYNC_STOCK_PRICE,
                    SYNC_INDICATORS,
                    SYNC_SIGNALS,
                    PRECOMPUTE_SYMBOL_FEATURES,
                    SYNC_INTRADAY_EOD ->
                    WorkType.SYMBOL;
            case SYNC_SYMBOLS, EVALUATE_SIGNALS -> WorkType.EXCHANGE;
            case PRECOMPUTE_SECTOR_FEATURES -> WorkType.SECTOR;
            default -> WorkType.GLOBAL;
        };
    }

    public static LocalDate workDay(JobExecutionHistory parent, Instant now) {
        Object value =
                parent.getMetaJson() == null ? null : parent.getMetaJson().get("workBusinessDate");
        return value == null ? now.atZone(ZONE).toLocalDate() : LocalDate.parse(value.toString());
    }

    public static List<SymbolKeyProjection> selectSymbols(
            JobDefinition job, List<SymbolKeyProjection> input) {
        Map<String, Object> config = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        Map<String, Object> scope = map(config.get("_expansionScope"));
        List<String> exchanges = JobConfigMapper.readStringList(config, "exchanges");
        var selected =
                input.stream()
                        .filter(
                                s -> {
                                    String exchange =
                                            s.getExchange().trim().toUpperCase(Locale.ROOT);
                                    if (!exchanges.isEmpty()
                                            && exchanges.stream()
                                                    .noneMatch(exchange::equalsIgnoreCase))
                                        return false;
                                    return scope.isEmpty()
                                            || "GLOBAL".equals(scope.get("type"))
                                            || ("EXCHANGE".equals(scope.get("type"))
                                                    && exchange.equals(scope.get("key")))
                                            || ("SYMBOL".equals(scope.get("type"))
                                                    && s.symbolKey()
                                                            .equalsIgnoreCase(
                                                                    String.valueOf(
                                                                            scope.get("key"))));
                                })
                        .sorted(Comparator.comparing(SymbolKeyProjection::symbolKey))
                        .toList();
        if (!scope.isEmpty() && !"GLOBAL".equals(scope.get("type")) && selected.isEmpty())
            throw new IllegalArgumentException("Explicit scope resolves no active symbol");
        if (selected.size() > MAX_WORK)
            throw new IllegalArgumentException("WORK_EXPANSION_BOUND_EXCEEDED");
        return selected;
    }

    /** Detached view: never change managed definition config or schedule state. */
    public static JobDefinition definition(JobDefinition original, Plan plan, LocalDate day) {
        JobDefinition view = new JobDefinition();
        view.setId(original.getId());
        view.setJobType(original.getJobType());
        view.setSource(
                JobDefinition.DataSource.valueOf(String.valueOf(plan.config().get("source"))));
        view.setFallbackSources(original.getFallbackSources());
        view.setCronExpr(original.getCronExpr());
        view.setTitle(original.getTitle());
        view.setIsActive(original.getIsActive());
        Map<String, Object> config = new LinkedHashMap<>(plan.config());
        config.put("evaluationDate", day.toString());
        view.setConfigJson(config);
        return view;
    }

    public static JobExecutionHistory parent(
            JobExecutionHistory original, JobDefinition job, LocalDate day) {
        JobExecutionHistory view = new JobExecutionHistory();
        view.setId(original.getId());
        view.setJob(job);
        view.setUsedSource(job.getSource());
        view.setParentLogId(original.getParentLogId());
        view.setJobRunId(original.getJobRunId());
        view.setJobRunStepIndex(original.getJobRunStepIndex());
        Map<String, Object> meta =
                new LinkedHashMap<>(
                        original.getMetaJson() == null ? Map.of() : original.getMetaJson());
        meta.put("workBusinessDate", day.toString());
        if (job.getJobType() == JobType.SYNC_INTRADAY_EOD)
            meta.put("metadataTarget", Map.of("tradingDate", day.toString()));
        view.setMetaJson(meta);
        return view;
    }

    public static Map<String, Object> parameters(JobMessage message, Map<String, Object> config) {
        Map<String, Object> result = new TreeMap<>();
        config.forEach(
                (k, v) -> {
                    if (!SELECTORS.contains(k)) result.put(k, v);
                });
        for (var component : message.getClass().getRecordComponents()) {
            if (!IDENTITY_FIELDS.contains(component.getName())) {
                try {
                    result.put(component.getName(), component.getAccessor().invoke(message));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        for (String key :
                List.of("sectorCodes", "focusSectorCodes", "indicators", "predictionHorizons")) {
            if (result.get(key) instanceof List<?> list)
                result.put(
                        key,
                        list.stream()
                                .distinct()
                                .sorted(Comparator.comparing(WorkExpansion::canonical))
                                .toList());
        }
        return freeze(result);
    }

    public static JobDefinition candidate(JobExecutionHistory execution) {
        JobDefinition original = execution.getJob();
        Map<String, Object> meta =
                execution.getMetaJson() == null ? Map.of() : execution.getMetaJson();
        if (!(meta.get("workExpansion") instanceof Map<?, ?> snapshot)) return original;
        Map<String, Object> config = map(snapshot.get("configuration"));
        var plan = new Plan(List.of(), config, Map.of());
        return definition(
                original, plan, LocalDate.parse(String.valueOf(snapshot.get("businessDate"))));
    }

    public static String identity(
            JobType type,
            WorkType unit,
            String key,
            LocalDate day,
            Map<String, Object> parameters) {
        return "work:"
                + sha256(
                        canonical(
                                List.of(
                                        type.name(),
                                        unit.name(),
                                        key,
                                        day.toString(),
                                        parameters)));
    }

    public static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            return sorted.entrySet().stream()
                    .map(e -> JSON.writeValueAsString(e.getKey()) + ":" + canonical(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list)
            return list.stream()
                    .map(WorkExpansion::canonical)
                    .collect(Collectors.joining(",", "[", "]"));
        if (value instanceof Number number)
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        if (value instanceof Boolean) return value.toString();
        return JSON.writeValueAsString(value.toString());
    }

    public static Map<String, Object> freeze(Map<String, Object> map) {
        TreeMap<String, Object> copy = new TreeMap<>();
        map.forEach((k, v) -> copy.put(k, freezeValue(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new TreeMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), freezeValue(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> l) return l.stream().map(WorkExpansion::freezeValue).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate date(Object value, LocalDate fallback) {
        return value == null ? fallback : LocalDate.parse(String.valueOf(value));
    }

    public static Map<String, Object> map(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("Expected an object");
        Map<String, Object> result = new LinkedHashMap<>();
        m.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static String required(Map<String, Object> value, String key) {
        Object v = value.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("Missing " + key);
        return v.toString().trim();
    }
}
