import java.nio.file.Files
import java.nio.file.LinkOption

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("dev.nx.gradle.project-graph") version ("0.1.12")
    kotlin("jvm") version "2.0.21"
}

group = "com.omni"
version = "0.0.1-SNAPSHOT"
description = "A server to manage upload file "

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

// Build tooling only; never extends the application or test runtime classpath.
val javaFormatter by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    javaFormatter("com.google.googlejavaformat:google-java-format:1.29.0")
}

tasks.register<JavaExec>("formatJava") {
    group = "formatting"
    description = "Formats the explicit Java file selection supplied by the Nx formatter."
    classpath = javaFormatter
    mainClass.set("com.google.googlejavaformat.java.Main")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    })
    jvmArgs(listOf("api", "code", "file", "parser", "tree", "util").map {
        "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
    })
    doFirst {
        val raw = System.getenv("OMNI_FORMAT_FILES")
            ?: throw GradleException("Use nx run platform:format or nx run omni:format.")
        val selected = groovy.json.JsonSlurper().parseText(raw) as? List<*>
            ?: throw GradleException("OMNI_FORMAT_FILES must be a JSON array.")
        require(selected.isNotEmpty() && selected.size <= 20) { "Expected 1..20 selected Java files." }
        val workspace = rootDir.toPath().resolve("../..").normalize().toRealPath()
        val roots = listOf("main", "test").map { workspace.resolve("apps/core/src/$it/java") }
        val files = selected.map { entry ->
            val relative = entry as? String ?: throw GradleException("Java paths must be strings.")
            require(Regex("apps/core/src/(main|test)/java/[\\s\\S]+\\.java").matches(relative)) {
                "Java file is outside Platform source roots: $relative"
            }
            val requested = workspace.resolve(relative).normalize()
            require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS)) {
                "Selected Java file is not a regular file: $relative"
            }
            val real = requested.toRealPath()
            require(roots.any { real.startsWith(it) }) { "Java path escapes source roots: $relative" }
            real.toString()
        }
        setArgs(listOf("--aosp", "--replace", "--skip-sorting-imports",
            "--skip-removing-unused-imports", "--skip-reflowing-long-strings", "--skip-javadoc-formatting") + files)
        files.forEach { logger.lifecycle("Formatting Java: $it") }
    }
}

extra["springModulithVersion"] = "2.0.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation(
        "org.springframework.modulith:spring-modulith-events-api:${
            property("springModulithVersion")
        }"
    )
    // Third party
    implementation("io.minio:minio:8.6.0")
    implementation("net.coobird:thumbnailator:0.4.1")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.springframework.boot:spring-boot-starter-kafka:4.0.0")
    implementation("io.hypersistence:hypersistence-utils-hibernate-71:3.15.3")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-actuator")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.register("coverage") {
    group = "verification"
    description = "Runs the complete Platform test suite and generates project-wide JaCoCo reports."
    dependsOn(tasks.jacocoTestReport)
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
