package com.omni.platform.modules.scheduler.repositories;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.ManualJobTrigger;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface ManualJobTriggerRepository extends BaseRepository<ManualJobTrigger> {

    Optional<ManualJobTrigger> findByActorAndIdempotencyKey(String actor, String idempotencyKey);
}
