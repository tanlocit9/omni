package com.omni.platform.application.repositories;

import com.omni.platform.application.entities.SyncConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Repository for {@link SyncConfig} entities.
 */
public interface SyncConfigRepository extends JpaRepository<SyncConfig, Long> {

    /**
     * Find a SyncConfig by its symbol.
     *
     * @param symbol the stock symbol
     * @return an Optional containing the SyncConfig if found
     */
    Optional<SyncConfig> findBySymbol(String symbol);
}