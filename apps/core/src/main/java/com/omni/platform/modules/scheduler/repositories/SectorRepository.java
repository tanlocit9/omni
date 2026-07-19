package com.omni.platform.modules.scheduler.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.Sector;
import com.omni.platform.modules.scheduler.repositories.projections.SectorMappingProjection;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SectorRepository extends BaseRepository<Sector> {

    Optional<Sector> findByCode(String code);

    Optional<Sector> findByTaxonomyAndTaxonomyLevelAndSourceCode(String taxonomy, Integer taxonomyLevel,
            String sourceCode);

    boolean existsByCode(String code);

    long countByIsActiveTrue();

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO sectors (code, name_vi, name_en, taxonomy, taxonomy_level, source_code, is_active, meta_json)
            SELECT
                data.code,
                data.name_vi,
                data.name_en,
                data.taxonomy,
                data.taxonomy_level,
                data.source_code,
                true,
                CAST(data.meta_json AS jsonb)
            FROM unnest(
                CAST(:codes AS text[]),
                CAST(:nameVis AS text[]),
                CAST(:nameEns AS text[]),
                CAST(:taxonomies AS text[]),
                CAST(:taxonomyLevels AS integer[]),
                CAST(:sourceCodes AS text[]),
                CAST(:metaJsons AS text[])
            ) AS data(code, name_vi, name_en, taxonomy, taxonomy_level, source_code, meta_json)
            ON CONFLICT ON CONSTRAINT uq_sectors_taxonomy_source_code
            DO UPDATE SET
                code = COALESCE(EXCLUDED.code, sectors.code),
                name_vi = COALESCE(EXCLUDED.name_vi, sectors.name_vi),
                name_en = COALESCE(EXCLUDED.name_en, sectors.name_en),
                taxonomy_level = COALESCE(EXCLUDED.taxonomy_level, sectors.taxonomy_level),
                is_active = true,
                meta_json = COALESCE(sectors.meta_json, '{}'::jsonb) || COALESCE(EXCLUDED.meta_json, '{}'::jsonb)
            """)
    void upsertBatch(@Param("codes") String[] codes,
            @Param("nameVis") String[] nameVis,
            @Param("nameEns") String[] nameEns,
            @Param("taxonomies") String[] taxonomies,
            @Param("taxonomyLevels") Integer[] taxonomyLevels,
            @Param("sourceCodes") String[] sourceCodes,
            @Param("metaJsons") String[] metaJsons);

    @Query(nativeQuery = true, value = """
            SELECT
                taxonomy AS taxonomy,
                taxonomy_level AS level,
                source_code AS sourceCode,
                code AS canonicalCode
            FROM sectors
            WHERE is_active = true
              AND taxonomy = :taxonomy
              AND taxonomy_level = :level
            ORDER BY code
            """)
    java.util.List<SectorMappingProjection> findActiveMappings(
            @Param("taxonomy") String taxonomy,
            @Param("level") Integer level);
}