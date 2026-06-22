package com.omni.platform.modules.scheduler.repositories;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.Symbol;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SymbolRepository extends BaseRepository<Symbol> {

    Optional<Symbol> findByCodeAndExchange(String code, String exchange);
}