package com.omni.platform.application.repositories;

import com.omni.platform.application.entities.UpdateLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link UpdateLog} entities.
 */
public interface UpdateLogRepository extends JpaRepository<UpdateLog, Long> {
    // Additional query methods can be defined here if needed
}