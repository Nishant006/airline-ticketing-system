# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build
#
# pom.xml is copied and dependencies resolved BEFORE the sources are copied, so
# that Docker caches the (slow, rarely-changing) dependency layer independently
# of the (fast, frequently-changing) source layer.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
COPY db/ db/
RUN ./mvnw -B -ntp clean package -DskipTests


# ---------------------------------------------------------------------------
# Stage 2 — runtime
#
# JRE-only base image, non-root user, no build tooling in the final image.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app
EXPOSE 8080

# Container-aware heap sizing; the JVM reads the cgroup memory limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
