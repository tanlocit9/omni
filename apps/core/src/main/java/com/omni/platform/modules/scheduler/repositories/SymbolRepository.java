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
      SELECT code, exchange FROM symbol
      WHERE is_active = true
        AND (CAST(:sectors AS text[]) IS NULL OR (meta_json ->> 'sector') = ANY(CAST(:sectors AS text[])))
      """)
  List<SymbolKeyProjection> findBySectors(@Param("sectors") String[] sectors);

  @Query(nativeQuery = true, value = """
      SELECT COUNT(id) FROM symbol
      WHERE is_active = true
        AND exchange = :exchange
      """)
  Integer countByExchange(@Param("exchange") String exchange);

  @Query(nativeQuery = true, value = """
      SELECT exchange, COUNT(id) FROM symbol
      WHERE is_active = true
      GROUP BY exchange
      """)
  List<Object[]> countAllActiveSymbolsGroupedByExchange();

  @Modifying
  @Query(nativeQuery = true, value = """
      INSERT INTO symbol (code, exchange, is_active, meta_json, created_at, updated_at)
      VALUES (:code, :exchange, true, CAST(:metaJson AS jsonb), now(), now())
      ON CONFLICT (code, exchange)
      DO UPDATE SET
          is_active = true,
          meta_json = symbol.meta_json || EXCLUDED.meta_json,
          updated_at = now()
      """)
  void upsertOne(@Param("code") String code,
      @Param("exchange") String exchange,
      @Param("metaJson") String metaJson);

  @Modifying
  @Query(nativeQuery = true, value = """
      UPDATE symbol
      SET is_active = false, updated_at = now()
      WHERE exchange = :exchange
        AND is_active = true
        AND code NOT IN (:codes)
      """)
  void deactivateMissing(@Param("exchange") String exchange, @Param("codes") Collection<String> codes);
}
