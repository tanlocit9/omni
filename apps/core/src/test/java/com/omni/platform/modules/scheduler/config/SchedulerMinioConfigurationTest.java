package com.omni.platform.modules.scheduler.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerMinioConfigurationTest {

    @Test
    void mapsSchedulerManifestStorageToSharedMinioEnvironmentContract() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        assertThat(property(sources, "app.minio.endpoint"))
                .isEqualTo("${MINIO_ENDPOINT:http://localhost:9000}");
        assertThat(property(sources, "app.minio.access-key"))
                .isEqualTo("${MINIO_ACCESS_KEY:minioadmin}");
        assertThat(property(sources, "app.minio.secret-key"))
                .isEqualTo("${MINIO_SECRET_KEY:minioadmin}");
        assertThat(property(sources, "app.minio.bucket"))
                .isEqualTo("${MINIO_BUCKET:stock-data}");
    }

    private Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
