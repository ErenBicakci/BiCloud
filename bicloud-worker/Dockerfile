# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/bicloud-worker-*.jar app.jar

EXPOSE 8081

# Worker, Docker socket'e erişmek için docker grubunda olmalı.
# Docker GID Linux'ta genellikle 999'dur — docker-compose'daki group_add ile eşleşmeli.
ENTRYPOINT ["java", "-jar", "app.jar"]
