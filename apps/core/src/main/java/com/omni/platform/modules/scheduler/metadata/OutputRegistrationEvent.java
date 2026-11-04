package com.omni.platform.modules.scheduler.metadata;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.shared.executions.WorkType;

/** I10 JSON adapter. Evidence collection does not establish READY or completion. */
public record OutputRegistrationEvent(
        int schemaVersion, UUID eventId, List<Registration> registrations) {
    public static final int MAX_BYTES = 262_144;
    public static final String TOPIC = "topic-register-output-metadata-v1";

    /** Reject coercions before Jackson can truncate a float or accept an abbreviated UUID. */
    public static void validateWire(tools.jackson.databind.JsonNode node) {
        require(node != null && node.isObject(), "Registration root must be an object");
        integer(node, "schemaVersion", true);
        uuid(node, "eventId");
        var members = node.get("registrations");
        require(members != null && members.isArray(), "Missing registrations");
        for (var member : members) {
            for (String field : List.of("registrationId", "executionId", "atomicAttemptId"))
                uuid(member, field);
            calendarDate(member.get("businessDate"));
            var outputs = member.get("outputs");
            require(outputs != null && outputs.isArray(), "Missing outputs");
            for (var output : outputs) {
                validateOutputWire(output);
            }
        }
    }

    public static void validateOutputWire(tools.jackson.databind.JsonNode output) {
        require(output != null && output.isObject(), "Missing output");
        integer(output, "rowCount", false);
        integer(output, "totalBytes", false);
        integer(output, "schemaVersion", true);
        var scope = output.get("workScope");
        if (scope != null && !scope.isNull()) {
            require(
                    scope.isObject()
                            && scope.has("atomicWorkId")
                            && scope.get("atomicWorkId").isString(),
                    "Invalid work scope");
            var keys = scope.get("rowKeys");
            require(keys != null && keys.isArray(), "Invalid row keys");
            for (var key : keys) {
                require(key.isObject(), "Invalid row key");
                for (var value : key) require(value.isString(), "Row coordinates must be strings");
            }
        }
        var dates = output.get("coverageDates");
        require(dates != null && dates.isArray(), "Missing coverage dates");
        for (var date : dates) calendarDate(date);
        var columns = output.get("columns");
        require(columns != null && columns.isArray(), "Missing columns");
        for (var column : columns)
            require(
                    column.has("nullable") && column.get("nullable").isBoolean(),
                    "Column nullable must be a boolean");
    }

    private static void integer(tools.jackson.databind.JsonNode node, String field, boolean int32) {
        var value = node.get(field);
        require(
                value != null
                        && value.isIntegralNumber()
                        && value.canConvertToLong()
                        && (!int32 || value.canConvertToInt()),
                "Invalid integral field: " + field);
    }

    private static void uuid(tools.jackson.databind.JsonNode node, String field) {
        var value = node.get(field);
        require(
                value != null
                        && value.isString()
                        && value.asText()
                                .matches(
                                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                "Invalid UUID: " + field);
    }

    private static void calendarDate(tools.jackson.databind.JsonNode value) {
        require(
                value != null
                        && value.isString()
                        && value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),
                "Business/coverage date must be an ISO calendar date");
        LocalDate.parse(value.asText());
    }

    public OutputRegistrationEvent {
        require(schemaVersion == 1 && eventId != null, "Unsupported registration envelope");
        bounded(registrations, 1, 32);
        registrations = List.copyOf(registrations);
        require(
                registrations.stream().map(Registration::registrationId).distinct().count()
                        == registrations.size(),
                "Duplicate registrationId");
        require(
                registrations.stream().map(Registration::executionId).distinct().count()
                        == registrations.size(),
                "Duplicate executionId");
    }

    public record LogicalOutput(String dataset, Map<String, String> partition) {
        public LogicalOutput {
            segment(dataset);
            require(partition != null, "Missing partition");
            partition.forEach(
                    (key, value) -> {
                        segment(key);
                        segment(value);
                    });
            partition = Map.copyOf(partition);
        }
    }

    public record Input(String dataset, Map<String, String> partition, String dataVersion) {
        public Input {
            var logical = new LogicalOutput(dataset, partition);
            partition = logical.partition();
            fingerprint(dataVersion);
        }
    }

    public record Column(String name, String type, Boolean nullable) {
        public Column {
            text(name, 128);
            text(type, 128);
            require(nullable != null, "Missing column nullability");
        }
    }

    public record WorkScope(String atomicWorkId, List<Map<String, String>> rowKeys) {
        public WorkScope {
            require(
                    atomicWorkId != null && atomicWorkId.matches("work:[0-9a-f]{64}"),
                    "Invalid scope identity");
            bounded(rowKeys, 1, 4096);
            require(new HashSet<>(rowKeys).size() == rowKeys.size(), "Duplicate row coordinates");
            rowKeys = rowKeys.stream().map(Map::copyOf).toList();
        }
    }

    public record Output(
            String dataset,
            Map<String, String> partition,
            String dataVersion,
            List<LocalDate> coverageDates,
            long rowCount,
            long totalBytes,
            int schemaVersion,
            String schemaHash,
            List<Column> columns,
            List<Input> inputs,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    WorkScope workScope) {
        public Output(
                String dataset,
                Map<String, String> partition,
                String dataVersion,
                List<LocalDate> coverageDates,
                long rowCount,
                long totalBytes,
                int schemaVersion,
                String schemaHash,
                List<Column> columns,
                List<Input> inputs) {
            this(
                    dataset,
                    partition,
                    dataVersion,
                    coverageDates,
                    rowCount,
                    totalBytes,
                    schemaVersion,
                    schemaHash,
                    columns,
                    inputs,
                    null);
        }

        public Output {
            var logical = new LogicalOutput(dataset, partition);
            partition = logical.partition();
            fingerprint(dataVersion);
            fingerprint(schemaHash);
            require(
                    rowCount > 0 && totalBytes > 0 && schemaVersion > 0,
                    "Invalid output statistics");
            bounded(coverageDates, 1, 4096);
            bounded(columns, 1, 256);
            bounded(inputs, 0, 128);
            require(
                    coverageDates.equals(coverageDates.stream().distinct().sorted().toList()),
                    "coverageDates must be sorted and unique");
            require(
                    columns.stream().map(Column::name).distinct().count() == columns.size(),
                    "Duplicate columns");
            if (workScope != null) {
                var keys =
                        dataset.equals("signals")
                                ? Set.of("symbol_key", "signal_date")
                                : Set.of("date");
                require(
                        Set.of("eod", "indicators", "signals").contains(dataset),
                        "Unsupported work dataset");
                require(
                        workScope.rowKeys().size() == rowCount,
                        "Work row count differs from scope");
                var days = new java.util.TreeSet<LocalDate>();
                for (var key : workScope.rowKeys()) {
                    boolean versionedSignal =
                            dataset.equals("signals")
                                    && key.keySet()
                                            .equals(
                                                    Set.of(
                                                            "symbol_key",
                                                            "signal_date",
                                                            "model_version"));
                    require(
                            key.keySet().equals(keys) || versionedSignal,
                            "Invalid work coordinates");
                    require(
                            key.values().stream().allMatch(v -> v != null && !v.isBlank()),
                            "Empty work coordinates");
                    String day = key.get(dataset.equals("signals") ? "signal_date" : "date");
                    require(day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "Invalid row date");
                    days.add(LocalDate.parse(day));
                }
                require(
                        List.copyOf(days).equals(coverageDates),
                        "Work coverage differs from coordinates");
            }
            coverageDates = List.copyOf(coverageDates);
            columns = List.copyOf(columns);
            inputs = List.copyOf(inputs);
        }

        public LogicalOutput identity() {
            return new LogicalOutput(dataset, partition);
        }
    }

    public record Registration(
            UUID registrationId,
            UUID executionId,
            UUID atomicAttemptId,
            String atomicWorkId,
            JobType jobType,
            WorkType workType,
            String workKey,
            LocalDate businessDate,
            Map<String, Object> parameters,
            List<LogicalOutput> requiredOutputs,
            List<Output> outputs,
            String validEmptyReason) {
        public Registration {
            require(
                    registrationId != null && executionId != null && atomicAttemptId != null,
                    "Missing registration/attempt/execution identity");
            require(
                    jobType != null
                            && workType != null
                            && businessDate != null
                            && parameters != null,
                    "Missing work tuple");
            text(workKey, 256);
            parameters = WorkExpansion.freeze(parameters);
            require(
                    WorkExpansion.identity(jobType, workType, workKey, businessDate, parameters)
                            .equals(atomicWorkId),
                    "atomicWorkId does not match work tuple");
            bounded(requiredOutputs, 0, 32);
            bounded(outputs, 0, 32);
            Set<LogicalOutput> required = new HashSet<>(requiredOutputs);
            var actual = outputs.stream().map(Output::identity).toList();
            require(
                    required.size() == requiredOutputs.size()
                            && new HashSet<>(actual).size() == actual.size(),
                    "Duplicate logical outputs");
            if (validEmptyReason != null) {
                text(validEmptyReason, 500);
                require(
                        outputs.isEmpty() && requiredOutputs.isEmpty(),
                        "Valid-empty cannot claim coverage");
            } else {
                require(
                        !required.isEmpty() && required.equals(new HashSet<>(actual)),
                        "Incomplete outputs");
            }
            require(
                    outputs.stream()
                            .allMatch(
                                    o ->
                                            o.workScope() == null
                                                    || atomicWorkId.equals(
                                                            o.workScope().atomicWorkId())),
                    "Output scope belongs to another work");
            requiredOutputs = List.copyOf(requiredOutputs);
            outputs = List.copyOf(outputs);
        }
    }

    private static void segment(String value) {
        require(
                value != null && value.matches("[a-z0-9][a-z0-9._-]{0,127}"),
                "Invalid logical segment");
    }

    private static void fingerprint(String value) {
        require(value != null && value.matches("sha256:[0-9a-f]{64}"), "Invalid fingerprint");
    }

    private static void text(String value, int max) {
        require(value != null && !value.isEmpty() && value.length() <= max, "Invalid text length");
    }

    private static void bounded(List<?> value, int min, int max) {
        require(
                value != null
                        && value.size() >= min
                        && value.size() <= max
                        && value.stream().noneMatch(java.util.Objects::isNull),
                "Invalid array bounds");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
