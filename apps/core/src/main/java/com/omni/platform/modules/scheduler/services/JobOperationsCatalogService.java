package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.config.ManualTriggerProperties;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.DependencySummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ExecutionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionDetail;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.PageResponse;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.BlockedJobRepository;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JobOperationsCatalogService {
    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobExecutionHistoryRepository executionRepository;
    private final BlockedJobRepository blockedJobRepository;
    private final ManualTriggerProperties triggerProperties;

    @Transactional(readOnly = true)
    public PageResponse<JobDefinitionSummary> list(
            String query, String jobType, Boolean active, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String type = jobType == null ? "" : jobType.trim().toUpperCase(Locale.ROOT);

        List<JobDefinition> matching = jobDefinitionRepository.findAll(Sort.by("id")).stream()
                .filter(job -> q.isEmpty() || searchable(job).contains(q))
                .filter(job -> type.isEmpty() || job.getJobType().name().equals(type))
                .filter(job -> active == null || active.equals(Boolean.TRUE.equals(job.getIsActive())))
                .toList();
        int from = Math.min(matching.size(), page * size);
        int to = Math.min(matching.size(), from + size);
        return new PageResponse<>(matching.subList(from, to).stream().map(this::summary).toList(),
                page, size, matching.size());
    }

    @Transactional(readOnly = true)
    public JobDefinitionDetail detail(UUID id) {
        JobDefinition job = findDefinition(id);
        Triggerability triggerability = triggerability(job);
        List<ExecutionSummary> recent = executionRepository
                .findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(id).stream()
                .map(JobOperationsCatalogService::execution).toList();
        return new JobDefinitionDetail(
                job.getId(), job.getTitle(), job.getSource().name(), job.getJobType().name(),
                job.getJobType().name(), stableWorkKey(job),
                Boolean.TRUE.equals(job.getIsActive()), job.getCronExpr(), job.getNextRun(),
                triggerability.allowed(), triggerability.reason(), recent.isEmpty() ? null : recent.get(0),
                dependencies(job), List.of(), recent);
    }

    public JobDefinition findDefinition(UUID id) {
        return jobDefinitionRepository.findById(id)
                .orElseThrow(() -> new JobOperationException(
                        HttpStatus.NOT_FOUND, "job_definition_not_found", "Job definition was not found"));
    }

    public static ExecutionSummary execution(JobExecutionHistory value) {
        return new ExecutionSummary(value.getId(), value.getStatus().name(), value.getTriggeredAt(),
                value.getStartedAt(), value.getFinishedAt(), value.getRecordsSynced(), value.getRecordsSkipped(),
                sanitize(value.getError()));
    }

    private JobDefinitionSummary summary(JobDefinition job) {
        Triggerability triggerability = triggerability(job);
        ExecutionSummary last = executionRepository
                .findFirstByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(job.getId())
                .map(JobOperationsCatalogService::execution).orElse(null);
        return new JobDefinitionSummary(
                job.getId(), job.getTitle(), job.getSource().name(), job.getJobType().name(),
                job.getJobType().name(), stableWorkKey(job),
                Boolean.TRUE.equals(job.getIsActive()), job.getCronExpr(), job.getNextRun(),
                triggerability.allowed(), triggerability.reason(), last);
    }

    private Triggerability triggerability(JobDefinition job) {
        if (!Boolean.TRUE.equals(job.getIsActive())) {
            return new Triggerability(false, "Job definition is inactive");
        }
        if (!triggerProperties.allows(job)) {
            return new Triggerability(false, "Job definition is not in the manual-trigger allow-list");
        }
        if (job.getClaimToken() != null && job.getClaimUntil() != null
                && job.getClaimUntil().isAfter(Instant.now())) {
            return new Triggerability(false, "Another scheduler or operator execution owns this job");
        }
        String name = job.getJobType().name() + "_" + job.getSource().name();
        return blockedJobRepository.findByJobNameAndResolvedFalse(name)
                .<Triggerability>map(blocked -> new Triggerability(false, sanitize(blocked.getBlockReason())))
                .orElseGet(() -> new Triggerability(true, null));
    }

    private static DependencySummary dependencies(JobDefinition job) {
        Map<String, Object> config = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        return new DependencySummary(strings(config.get("dependsOnJobs")),
                datasetNames(config.get("dependsOnDatasets")), datasetNames(config.get("producesDatasets")));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static List<String> datasetNames(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("dataset") != null) {
                result.add(String.valueOf(map.get("dataset")));
            } else if (item instanceof String text && !text.isBlank()) {
                result.add(text);
            }
        }
        return result.stream().distinct().toList();
    }

    private static String searchable(JobDefinition job) {
        return String.join(" ", job.getId().toString(), String.valueOf(job.getTitle()),
                job.getSource().name(), job.getJobType().name()).toLowerCase(Locale.ROOT);
    }

    private static String stableWorkKey(JobDefinition job) {
        return job.getJobType().name() + ":" + job.getSource().name();
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private record Triggerability(boolean allowed, String reason) {
    }
}
