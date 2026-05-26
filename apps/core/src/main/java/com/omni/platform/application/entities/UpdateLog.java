package com.omni.platform.application.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity representing the update_log table.
 * Records each execution run, tracking status, duration, records inserted/updated/skipped, and error messages.
 */
@Entity
@Table(name = "update_log")
public class UpdateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbol that was synchronized */
    @Column(nullable = false)
    private String symbol;

    /** Sync status: success / error */
    @Column(nullable = false)
    private String status;

    /** Number of records inserted */
    @Column(name = "records_inserted")
    private Integer recordsInserted;

    /** Number of records updated */
    @Column(name = "records_updated")
    private Integer recordsUpdated;

    /** Number of records skipped (duplicates) */
    @Column(name = "records_skipped")
    private Integer recordsSkipped;

    /** Total records after sync */
    @Column(name = "total_records")
    private Integer totalRecords;

    /** Duration in milliseconds */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Optional error message */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Timestamp of the log entry */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRecordsInserted() {
        return recordsInserted;
    }

    public void setRecordsInserted(Integer recordsInserted) {
        this.recordsInserted = recordsInserted;
    }

    public Integer getRecordsUpdated() {
        return recordsUpdated;
    }

    public void setRecordsUpdated(Integer recordsUpdated) {
        this.recordsUpdated = recordsUpdated;
    }

    public Integer getRecordsSkipped() {
        return recordsSkipped;
    }

    public void setRecordsSkipped(Integer recordsSkipped) {
        this.recordsSkipped = recordsSkipped;
    }

    public Integer getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(Integer totalRecords) {
        this.totalRecords = totalRecords;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}