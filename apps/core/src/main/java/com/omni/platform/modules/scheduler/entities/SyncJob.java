package com.omni.platform.modules.scheduler.entities;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.omni.platform.shared.entities.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sync_job", uniqueConstraints = @UniqueConstraint(columnNames = { "source",
        "table_name" }), indexes = @Index(name = "idx_sync_job_next_run", columnList = "next_run, is_active"))
@Getter
@Setter
public class SyncJob extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fallback_sources", columnDefinition = "jsonb")
    private List<DataSource> fallbackSources = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(name = "cron_expr")
    private String cronExpr;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "next_run")
    private Instant nextRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private Map<String, Object> configJson;

    @OneToMany(mappedBy = "job", fetch = FetchType.LAZY)
    private List<SyncJobLog> logs = new ArrayList<>();

    public enum DataSource {
        VCI, VNSTOCK, TCBS, SSI, FIREANT, VND
    }

    public enum JobType {
        STOCK_PRICE,
        STOCK_INDEX,
        FINANCIAL_REPORT
    }
}