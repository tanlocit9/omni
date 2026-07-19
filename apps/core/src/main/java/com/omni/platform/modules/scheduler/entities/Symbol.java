package com.omni.platform.modules.scheduler.entities;

import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.omni.platform.shared.entities.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "symbols", uniqueConstraints = @UniqueConstraint(columnNames = { "code", "exchange" }))
@Getter
@Setter
public class Symbol extends BaseEntity {

    public static final List<String> META_JSON_ALLOWED_KEYS = List.of(
            "companyName",
            "listedDate",
            "sectorTaxonomy",
            "sectorLevel",
            "sourceSectorCode",
            "sectorLv1Code",
            "sectorLv2Code",
            "sectorLv3Code",
            "sectorLv4Code",
            "classificationUpdatedAt");

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 50)
    private String exchange;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private java.util.Map<String, Object> metaJson;
}