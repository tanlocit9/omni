package com.omni.platform.modules.scheduler.repositories.projections;

public interface SectorMappingProjection {
    String getTaxonomy();

    Integer getLevel();

    String getSourceCode();

    String getCanonicalCode();
}