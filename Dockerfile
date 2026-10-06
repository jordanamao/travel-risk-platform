# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Resolve dependencies in their own layer so source-only changes reuse it.
# The cache mount also keeps ~/.m2 between builds when pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
    && cp target/travel-risk-platform-*.jar app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S -G app -h /app app

COPY --chown=app:app docker-entrypoint.sh ./docker-entrypoint.sh
COPY --from=build --chown=app:app /app/app.jar app.jar
RUN chmod +x ./docker-entrypoint.sh

ENV SPRING_CACHE_TYPE=simple \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER app
EXPOSE 8080

ENTRYPOINT ["./docker-entrypoint.sh"]
