package com.omni.platform.modules.scheduler.metadata;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private authenticated metadata API, shared by worker and query adapters. */
@RestController
@RequestMapping("/api/v1/jobs/output-readiness")
public class OutputReadinessController {
    private final OutputReadinessProvider provider;
    public OutputReadinessController(OutputReadinessProvider provider) { this.provider = provider; }

    @GetMapping
    public Map<String,Object> page(@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="200") int limit, @RequestParam(required=false) Long revision) {
        return provider.page(offset, limit, revision);
    }
}
