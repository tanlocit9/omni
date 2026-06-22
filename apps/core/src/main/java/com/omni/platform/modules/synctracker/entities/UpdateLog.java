package com.omni.platform.modules.synctracker.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity representing the update_log table.
 * Records each execution run, tracking status, duration, records inserted/updated/skipped, and error messages.
 */
@Setter
@Getter
@Entity
@Table(name = "update_log")
public class UpdateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Symbol that was synchronized
     */
    @Column(nullable = false)
    private String symbol;

    /**
     * Sync status: success / error
     */
    @Column(nullable = false)
    private String status;

    /**
     * Number of records inserted
     */
    @Column(name = "records_inserted")
    private Integer recordsInserted;

    /**
     * Number of records updated
     */
    @Column(name = "records_updated")
    private Integer recordsUpdated;

    /**
     * Number of records skipped (duplicates)
     */
    @Column(name = "records_skipped")
    private Integer recordsSkipped;

    /**
     * Total records after sync
     */
    @Column(name = "total_records")
    private Integer totalRecords;

    /**
     * Duration in milliseconds
     */
    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Optional error message
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Timestamp of the log entry
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}