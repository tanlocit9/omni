package com.omni.platform.modules.scheduler.repositories;

import java.util.Optional;

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