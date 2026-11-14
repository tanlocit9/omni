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

    public OutputBackfillController(
            OutputBackfillService service, tools.jackson.databind.json.JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping("/fence")
    public Map<String, Long> fence() {
        return Map.of("fence", service.begin());
    }

    @PostMapping
    public Map<String, Boolean> commit(@RequestBody String payload) {
        OutputRegistrationEvent.require(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= OutputRegistrationEvent.MAX_BYTES,
                "Baseline exceeds size bound");
        var node = json.readTree(payload);
        OutputRegistrationEvent.require(
                node.isObject()
                        && node.has("fence")
                        && node.get("fence").isIntegralNumber()
                        && node.get("fence").canConvertToLong(),
                "Invalid baseline fence");
        OutputRegistrationEvent.validateOutputWire(node.get("output"));
        OutputBackfillService.Baseline baseline =
                json.readerFor(OutputBackfillService.Baseline.class)
                        .with(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(payload);
        service.commit(baseline);
        return Map.of("accepted", true);
    }

    @PostMapping("/storage")
    public Map<String, Long> pinStorage(@RequestBody String payload) {
        OutputRegistrationEvent.require(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16384,
                "Storage baseline exceeds size bound");
        var node = json.readTree(payload);
        for (String field : java.util.List.of("fence", "revision", "totalBytes"))
            OutputRegistrationEvent.require(
                    node.has(field)
                            && node.get(field).isIntegralNumber()
                            && node.get(field).canConvertToLong(),
                    "Invalid storage baseline " + field);
        for (String field : java.util.List.of("dataset", "storageVersionId", "checksum"))
            OutputRegistrationEvent.require(
                    node.has(field) && node.get(field).isString(),
                    "Invalid storage baseline " + field);
        OutputRegistrationEvent.require(
                node.has("partition") && node.get("partition").isObject(),
                "Invalid storage baseline partition");
        for (var value : node.get("partition"))
            OutputRegistrationEvent.require(
                    value.isString(), "Invalid storage baseline partition value");
        OutputBackfillService.StorageBaseline baseline =
                json.readerFor(OutputBackfillService.StorageBaseline.class)
                        .with(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(payload);
        return Map.of("revision", service.pinStorage(baseline));
    }
}
