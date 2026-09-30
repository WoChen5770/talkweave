ARG BUILDER_IMAGE=maven:3.9.8-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy
FROM --platform=$BUILDPLATFORM ${BUILDER_IMAGE} AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY Dockerfile compose.yml .dockerignore ./
COPY .github/workflows/container.yml ./.github/workflows/container.yml
COPY scripts/ci ./scripts/ci
RUN mvn -B -Dstyle.color=never verify

FROM ${RUNTIME_IMAGE}
ARG VCS_REF=unknown
ARG SOURCE_URL
LABEL org.opencontainers.image.revision=$VCS_REF org.opencontainers.image.source=$SOURCE_URL
WORKDIR /app
RUN groupadd --gid 10001 assistant && useradd --uid 10001 --gid 10001 --no-create-home assistant \
    && mkdir -p /app/materials /app/config && chown 10001:10001 /app/materials && chmod 700 /app/materials
COPY --from=build /build/target/talkweave-0.1.0-SNAPSHOT.jar /app/assistant.jar
USER 10001:10001
ENV MANAGED_MATERIALS_DIRECTORY=/app/materials EXTERNAL_SERVICES_CONFIG=/app/config/external-services.yml HEALTH_PORT=8081 ADMIN_ADDRESS=0.0.0.0
EXPOSE 8680
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["java", "-Dloader.main=io.github.wochen5770.talkweave.runtime.HealthCheck", "-cp", "/app/assistant.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/assistant.jar"]
