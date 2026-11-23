# ============================================================
# UpLink API — Multi-stage Docker build
# Target: Render free tier (512 MB RAM, 0.1 vCPU)
# Java: Eclipse Temurin 17 (LTS)
# ============================================================

# ---- Stage 1: Build ----------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper + POM first (layer-cached, only re-runs on dependency changes)
COPY mvnw pom.xml ./
COPY .mvn .mvn

# Download all dependencies (cached layer unless pom.xml changes)
RUN ./mvnw dependency:go-offline -q

# Copy source and build fat JAR, skip tests (run them in CI separately)
COPY src src
RUN ./mvnw package -DskipTests -q

# ---- Stage 2: Runtime --------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy only the fat JAR from builder — keeps image lean (~180 MB vs ~400 MB)
COPY --from=builder /app/target/UpLinkapi-0.0.1-SNAPSHOT.jar app.jar

# Render injects PORT env var; application.properties reads it via ${PORT:8081}
EXPOSE 8080

# JVM flags tuned for 512 MB Render free-tier container:
#   -Xmx256m          : cap heap at 256 MB (leaves room for metaspace + threads)
#   -Xms64m           : start small so the dyno wakes up fast
#   -XX:+UseContainerSupport : respect cgroup memory limits (not host RAM)
#   -XX:MaxRAMPercentage=60  : cap heap at 60% of container RAM as a safety net
#   -Djava.security.egd=...  : faster SecureRandom startup (Tomcat/HTTPS needs it)
ENTRYPOINT ["java", \
  "-Xmx256m", \
  "-Xms64m", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=60.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
