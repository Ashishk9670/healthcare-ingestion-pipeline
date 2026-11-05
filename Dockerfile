# syntax=docker/dockerfile:1
# Multi-stage build; the runtime image runs as a non-root user. SYNTHETIC DATA ONLY.
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/healthcare-ingestion-pipeline-*.jar /workspace/app.jar \
    && java -Djarmode=tools -jar /workspace/app.jar extract --layers --destination /workspace/layers

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && addgroup -S -g 10001 hdip && adduser -S -u 10001 -G hdip hdip
WORKDIR /app
COPY --from=build --chown=hdip:hdip /workspace/layers/dependencies/ ./
COPY --from=build --chown=hdip:hdip /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=hdip:hdip /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=hdip:hdip /workspace/layers/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Secrets (DB_PASSWORD, PHI_ENCRYPTION_KEYS, PHI_HMAC_KEY, ...) are supplied at runtime, never baked in.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
