package com.omni.platform.application.controllers;

import com.omni.platform.application.dtos.stock.StockSyncRequest;
import com.omni.platform.application.entities.SyncConfig;
import com.omni.platform.application.repositories.SyncConfigRepository;
import com.omni.platform.infrastructure.kafka.StockSyncProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST controller that triggers a stock synchronization via Kafka.
 */
@RestController
@RequestMapping("/api/stocks")
public class StockSyncController {

    private final SyncConfigRepository syncConfigRepository;
    private final StockSyncProducer stockSyncProducer;

    public StockSyncController(SyncConfigRepository syncConfigRepository,
                               StockSyncProducer stockSyncProducer) {
        this.syncConfigRepository = syncConfigRepository;
        this.stockSyncProducer = stockSyncProducer;
    }

    /**
     * Trigger a sync for a given symbol.
     *
     * @param symbol the stock symbol (e.g., "XYZ")
     * @return HTTP 202 Accepted if the request was queued
     */
    @PostMapping("/sync")
    public ResponseEntity<String> syncStock(@RequestParam String symbol) {
        // Determine the limit from the sync_config table; fallback to a default if not configured
        Optional<SyncConfig> optConfig = syncConfigRepository.findBySymbol(symbol);
        int limit = optConfig.map(SyncConfig::getMaxLimit).orElse(10); // default limit

        StockSyncRequest request = new StockSyncRequest(symbol, limit);
        stockSyncProducer.sendSyncRequest(request);

        return ResponseEntity.accepted()
                .body("Sync request for symbol " + symbol + " with limit " + limit + " has been queued.");
    }
}