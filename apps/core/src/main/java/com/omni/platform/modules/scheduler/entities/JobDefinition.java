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
@Table(name = "job_definitions", uniqueConstraints = @UniqueConstraint(name = "uq_job_definition_source_type", columnNames = {
        "source",
        "job_type",
        "cron_expr" }), indexes = @Index(name = "idx_job_definition_next_run", columnList = "next_run, is_active"))
@Getter
@Setter
public class JobDefinition extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fallback_sources", columnDefinition = "jsonb")
    private List<DataSource> fallbackSources = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(name = "title")
    private String title;

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
    private List<JobExecutionHistory> executionHistory = new ArrayList<>();

    public enum DataSource {
        VCI, VNSTOCK, TCBS, SSI, FIREANT, VND, ANALYZER
    }

    public enum JobType {
        SYNC_STOCK_PRICE,
        SYNC_SYMBOLS,
        SYNC_INDICATORS,
        SYNC_SIGNALS,
        EVALUATE_SIGNALS,
        PRECOMPUTE_SYMBOL_FEATURES,
        PRECOMPUTE_SECTOR_FEATURES,
        SECTOR_ROTATION_BACKTEST,
        SECTOR_TRANSITION_ANALYZE,
        SECTOR_TRANSITION_EVALUATE_OUTCOMES
    }
}