package com.omni.platform.modules.scheduler.controllers;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ExecutionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionDetail;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.PageResponse;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;
import com.omni.platform.modules.scheduler.services.JobOperationException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobOperationsController {
    private final JobOperationsCatalogService catalogService;

    @GetMapping("/definitions")
    public PageResponse<JobDefinitionSummary> definitions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return catalogService.list(q, jobType, active, page, size);
    }

    @GetMapping("/definitions/{definitionId}")
    public JobDefinitionDetail definition(@PathVariable UUID definitionId) {
        return catalogService.detail(definitionId);
    }

    @PostMapping("/definitions/{definitionId}/triggers")
    public void trigger(@PathVariable UUID definitionId) {
        throw manualTriggerDeferred();
    }

    @GetMapping("/triggers/{requestId}")
    public void triggerStatus(@PathVariable UUID requestId) {
        throw manualTriggerDeferred();
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionSummary execution(@PathVariable UUID executionId) {
        return catalogService.executionStatus(executionId);
    }

    private static JobOperationException manualTriggerDeferred() {
        return new JobOperationException(
                HttpStatus.GONE, "manual_trigger_deferred", "Manual job triggering is deferred");
    }
}
