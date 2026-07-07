package com.omni.platform.modules.scheduler.repositories.projections;

public interface SymbolKeyProjection {
    String getCode();

    String getExchange();

    default String symbolKey() {
        return getExchange() + "-" + getCode();
    }
}