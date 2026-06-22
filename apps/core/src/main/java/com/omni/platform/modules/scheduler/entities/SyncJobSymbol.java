package com.omni.platform.modules.scheduler.entities;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sync_job_symbol", indexes = @Index(name = "idx_sync_job_symbol_job_id", columnList = "job_id"))
@Getter
@Setter
public class SyncJobSymbol {

    @EmbeddedId
    private SyncJobSymbolId id = new SyncJobSymbolId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("jobId")
    @JoinColumn(name = "job_id")
    private SyncJob job;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("symbolId")
    @JoinColumn(name = "symbol_id")
    private Symbol symbol;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "last_offset")
    private Instant lastOffset;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Embeddable
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    public static class SyncJobSymbolId implements Serializable {

        @Column(name = "job_id")
        private UUID jobId;

        @Column(name = "symbol_id")
        private UUID symbolId;
    }
}