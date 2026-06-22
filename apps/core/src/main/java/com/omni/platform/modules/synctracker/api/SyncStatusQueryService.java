package com.omni.platform.modules.synctracker.api;

import com.omni.platform.modules.synctracker.entities.UpdateLog;
import com.omni.platform.modules.synctracker.repositories.UpdateLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query service providing read-only access to sync status logs.
 * This is the public API of the synctracker module for other modules
 * or REST controllers to query historical sync results.
 */
@Service
public class SyncStatusQueryService {

    private final UpdateLogRepository updateLogRepository;

    public SyncStatusQueryService(UpdateLogRepository updateLogRepository) {
        this.updateLogRepository = updateLogRepository;
    }

    /**
     * Return all sync log entries, ordered by most recent first.
     *
     * @return list of all {@link UpdateLog} entries
     */
    public List<UpdateLog> findAll() {
        return updateLogRepository.findAll();
    }

    /**
     * Return all sync log entries for a specific symbol.
     *
     * @param symbol the stock symbol to filter by
     * @return list of {@link UpdateLog} entries for the symbol
     */
    public List<UpdateLog> findBySymbol(String symbol) {
        return updateLogRepository.findBySymbol(symbol);
    }
}