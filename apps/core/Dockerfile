# Multi-stage build for Java Spring Boot application
FROM gradle:8-jdk21 AS builder

WORKDIR /build

# Copy gradle files for dependency caching
COPY apps/core/build.gradle apps/core/settings.gradle /build/
COPY apps/core/gradle /build/gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY apps/core/src /build/src

# Build the application
RUN gradle bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy the built JAR from builder stage
COPY --from=builder /build/build/libs/*.jar /app/application.jar

EXPOSE 8080

# Run the Spring Boot application
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
CMD ["--spring.profiles.active=dev"]