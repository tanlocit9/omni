package com.omni.platform.modules.synctracker.repositories;

import com.omni.platform.modules.synctracker.entities.UpdateLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link UpdateLog} entities.
 */
public interface UpdateLogRepository extends JpaRepository<UpdateLog, Long> {

    /**
     * Find all update log entries for the given stock symbol.
     *
     * @param symbol the stock symbol
     * @return list of matching log entries
     */
    List<UpdateLog> findBySymbol(String symbol);
}
