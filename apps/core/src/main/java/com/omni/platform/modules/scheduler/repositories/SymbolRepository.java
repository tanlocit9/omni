package com.omni.platform.modules.scheduler.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
      SELECT s.code, s.exchange FROM symbols s
      LEFT JOIN sectors sec ON sec.id = s.sector_id
      WHERE s.is_active = true
        AND (CAST(:sectors AS text[]) IS NULL OR sec.code = ANY(CAST(:sectors AS text[])))
      """)
  List<SymbolKeyProjection> findBySectors(@Param("sectors") String[] sectors);

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
      INSERT INTO symbols (code, exchange, is_active, sector_id, meta_json, created_at, updated_at)
      VALUES (:code, :exchange, true, :sectorId, CAST(:metaJson AS jsonb), now(), now())
      ON CONFLICT (code, exchange)
      DO UPDATE SET
          is_active = true,
          sector_id = CASE
              WHEN EXCLUDED.sector_id IS NOT NULL THEN EXCLUDED.sector_id
              ELSE symbols.sector_id
          END,
          meta_json = symbols.meta_json || EXCLUDED.meta_json,
          updated_at = now()
      """)
  void upsertOne(@Param("code") String code,
      @Param("exchange") String exchange,
      @Param("sectorId") UUID sectorId,
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
}