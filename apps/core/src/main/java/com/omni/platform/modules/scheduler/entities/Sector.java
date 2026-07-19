package com.omni.platform.modules.scheduler.entities;

import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.omni.platform.shared.entities.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sectors", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sectors_taxonomy_source_code", columnNames = { "taxonomy", "source_code" }),
        @UniqueConstraint(name = "uq_sectors_taxonomy_level_code", columnNames = { "taxonomy", "taxonomy_level", "code" })
})
@Getter
@Setter
public class Sector extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String code;

    @Column(name = "name_vi")
    private String nameVi;

    @Column(name = "name_en")
    private String nameEn;

    @Column(nullable = false, length = 50)
    private String taxonomy;

    @Column(name = "taxonomy_level", nullable = false)
    private Integer taxonomyLevel;

    @Column(name = "source_code", nullable = false, length = 100)
    private String sourceCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Sector parent;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private Map<String, Object> metaJson;
}