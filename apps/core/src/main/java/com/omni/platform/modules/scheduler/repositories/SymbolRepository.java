package com.omni.platform.modules.scheduler.repositories;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.Symbol;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SymbolRepository extends BaseRepository<Symbol> {

        @Query(nativeQuery = true, value = """
                        SELECT COUNT(id) FROM symbols
                        WHERE is_active = true
                          AND exchange = :exchange
                        """)
        Integer countByExchange(@Param("exchange") String exchange);

        @Query(nativeQuery = true, value = """
                        SELECT exchange, COUNT(id) FROM symbols
                        WHERE is_active = true
                        GROUP BY exchange
                        """)
        List<Object[]> countAllActiveSymbolsGroupedByExchange();

        @Modifying
        @Query(nativeQuery = true, value = """
                        INSERT INTO symbols (code, exchange, is_active, meta_json, created_at, updated_at)
                        VALUES (:code, :exchange, true, CAST(:metaJson AS jsonb), now(), now())
                        ON CONFLICT (code, exchange)
                        DO UPDATE SET
                            is_active = true,
                            meta_json = CAST(:metaJson AS jsonb),
                            updated_at = now()
                        """)
        void upsertOne(@Param("code") String code,
                        @Param("exchange") String exchange,
                        @Param("metaJson") String metaJson);

        @Modifying
        @Query(nativeQuery = true, value = """
                        UPDATE symbols
                        SET is_active = false, updated_at = now()
                        WHERE exchange = :exchange
                          AND is_active = true
                          AND code NOT IN (:codes)
                        """)
        void deactivateMissing(@Param("exchange") String exchange, @Param("codes") Collection<String> codes);

        default List<SymbolKeyProjection> findBySectorCodesAndLevel(String[] sectorCodes, int sectorLevel) {
                if (sectorCodes == null || sectorCodes.length == 0) {
                        return findAllActiveSymbolKeys();
                }
                String jsonKey = "sectorLv" + sectorLevel + "Code";
                return findBySectorCodesAndLevelKey(sectorCodes, jsonKey);
        }

        @Query(value = """
                        SELECT code, exchange
                        FROM symbols
                        WHERE is_active = TRUE
                        """, nativeQuery = true)
        List<SymbolKeyProjection> findAllActiveSymbolKeys();

        @Query(value = """
                        SELECT code, exchange
                        FROM symbols
                        WHERE is_active = TRUE
                          AND meta_json ->> CAST(:jsonKey AS text) = ANY(CAST(:sectorCodes AS text[]))
                        """, nativeQuery = true)
        List<SymbolKeyProjection> findBySectorCodesAndLevelKey(
                        @Param("sectorCodes") String[] sectorCodes,
                        @Param("jsonKey") String jsonKey);
        default List<String> findDistinctSectorCodesByLevel(String[] sectorCodes, int sectorLevel) {
                String jsonKey = "sectorLv" + sectorLevel + "Code";
                if (sectorCodes == null || sectorCodes.length == 0) {
                        return findAllDistinctSectorCodesByLevelKey(jsonKey);
                }
                return findDistinctSectorCodesByLevelKey(sectorCodes, jsonKey);
        }

        @Query(value = """
                        SELECT DISTINCT meta_json ->> CAST(:jsonKey AS text) AS sector_code
                        FROM symbols
                        WHERE is_active = TRUE
                          AND meta_json ->> CAST(:jsonKey AS text) IS NOT NULL
                        ORDER BY sector_code
                        """, nativeQuery = true)
        List<String> findAllDistinctSectorCodesByLevelKey(@Param("jsonKey") String jsonKey);

        @Query(value = """
                        SELECT DISTINCT meta_json ->> CAST(:jsonKey AS text) AS sector_code
                        FROM symbols
                        WHERE is_active = TRUE
                          AND meta_json ->> CAST(:jsonKey AS text) IS NOT NULL
                          AND meta_json ->> CAST(:jsonKey AS text) = ANY(CAST(:sectorCodes AS text[]))
                        ORDER BY sector_code
                        """, nativeQuery = true)
        List<String> findDistinctSectorCodesByLevelKey(
                        @Param("sectorCodes") String[] sectorCodes,
                        @Param("jsonKey") String jsonKey);
}