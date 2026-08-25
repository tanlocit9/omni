package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManagerFactory;

@ActiveProfiles(value = "test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:platform-application-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class PlatformApplicationTests {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void contextLoads() {
    }

    @Test
    void registersModulithEventPublicationEntity() {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entityType -> entityType.getJavaType().getSimpleName())
                .contains("DefaultJpaEventPublication");
    }

}
