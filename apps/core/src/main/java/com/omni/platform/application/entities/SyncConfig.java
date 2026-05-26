package com.omni.platform.application.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity representing the sync_config table.
 * Holds metadata about schedules and configurations for each source/table.
 */
@Entity
@Table(name = "sync_config")
public class SyncConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbol or source identifier, e.g., stock ticker */
    @Column(nullable = false, unique = true)
    private String symbol;

    /** Maximum number of records to fetch per sync */
    @Column(name = "max_limit", nullable = false)
    private Integer maxLimit;

    /** Timestamp of the last successful sync */
    @Column(name = "last_success")
    private LocalDateTime lastSuccess;

    /** Additional JSON configuration (optional) */
    @Column(columnDefinition = "TEXT")
    private String configJson;

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Integer getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(Integer maxLimit) {
        this.maxLimit = maxLimit;
    }

    public LocalDateTime getLastSuccess() {
        return lastSuccess;
    }

    public void setLastSuccess(LocalDateTime lastSuccess) {
        this.lastSuccess = lastSuccess;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }
}