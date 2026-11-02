package com.omni.platform.modules.scheduler.metadata;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs/output-readiness/backfill")
public class OutputBackfillController {
    private final OutputBackfillService service;
    private final tools.jackson.databind.json.JsonMapper json;
    public OutputBackfillController(OutputBackfillService service, tools.jackson.databind.json.JsonMapper json) {
        this.service=service; this.json=json;
    }
    @PostMapping("/fence")
    public Map<String,Long> fence() { return Map.of("fence",service.begin()); }
    @PostMapping
    public Map<String,Boolean> commit(@RequestBody String payload) {
        OutputRegistrationEvent.require(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= OutputRegistrationEvent.MAX_BYTES,
                "Baseline exceeds size bound");
        var node=json.readTree(payload);
        OutputRegistrationEvent.require(node.isObject() && node.has("fence") && node.get("fence").isIntegralNumber()
                && node.get("fence").canConvertToLong(), "Invalid baseline fence");
        OutputRegistrationEvent.validateOutputWire(node.get("output"));
        OutputBackfillService.Baseline baseline=json.readerFor(OutputBackfillService.Baseline.class)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload);
        service.commit(baseline); return Map.of("accepted",true);
    }
}
