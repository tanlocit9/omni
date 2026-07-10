package com.omni.platform.modules.scheduler.seeders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.SectorSeedConfig;
import com.omni.platform.modules.scheduler.repositories.SectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SectorSeeder implements CommandLineRunner {

    private final SectorRepository sectorRepository;

    @Value("${app.seed.sectors.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Sector seeding disabled (app.seed.sectors.enabled=false), skipping.");
            return;
        }

        if (sectorRepository.count() > 0) {
            log.info("Sector table is not empty, preserving database mapping as runtime source of truth.");
            return;
        }

        SectorSeedConfig.SECTOR_SEEDS.stream()
                .map(SectorSeedConfig.SectorSeed::toEntity)
                .forEach(sectorRepository::save);

        log.info("Seeded [{}] canonical sector mappings.", SectorSeedConfig.SECTOR_SEEDS.size());
    }
}