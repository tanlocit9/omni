package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.SyncJobSymbol;
import com.omni.platform.modules.scheduler.entities.SyncJobSymbol.SyncJobSymbolId;

@Repository
public interface SyncJobSymbolRepository extends JpaRepository<SyncJobSymbol, SyncJobSymbolId> {
    @Query("""
                SELECT sjs FROM SyncJobSymbol sjs
                JOIN FETCH sjs.symbol
                WHERE sjs.job.id = :jobId
                AND sjs.isActive = true
            """)
    List<SyncJobSymbol> findActiveByJobId(@Param("jobId") UUID jobId);

    List<SyncJobSymbol> findBySymbolId(UUID symbolId);

    Optional<SyncJobSymbol> findByJobIdAndSymbolId(UUID jobId, UUID symbolId);
}