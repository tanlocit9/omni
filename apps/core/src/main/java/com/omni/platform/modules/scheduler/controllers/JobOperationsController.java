package com.omni.platform.modules.scheduler.controllers;

import java.security.Principal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ExecutionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionDetail;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.JobDefinitionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerRequest;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerResponse;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.PageResponse;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.TriggerStatusResponse;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;
import com.omni.platform.modules.scheduler.services.ManualJobTriggerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobOperationsController {
    private final JobOperationsCatalogService catalogService;
    private final ManualJobTriggerService triggerService;

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
    public ResponseEntity<ManualTriggerResponse> trigger(
            @PathVariable UUID definitionId,
            @RequestBody ManualTriggerRequest request,
            Principal principal) {
        ManualTriggerResponse response = triggerService.trigger(definitionId, principal.getName(), request);
        HttpStatus status = switch (response.state()) {
            case "ACCEPTED", "BLOCKED" -> HttpStatus.ACCEPTED;
            case "CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/triggers/{requestId}")
    public TriggerStatusResponse triggerStatus(@PathVariable UUID requestId, Principal principal) {
        return triggerService.triggerStatus(requestId, principal.getName());
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionSummary execution(@PathVariable UUID executionId) {
        return triggerService.executionStatus(executionId);
    }
}
