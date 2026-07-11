# ============================================================
# Stage 1: Build
# Official Maven image bundles JDK 21 and Maven 3.9 on Alpine.
# No Maven Wrapper needed — mvn is on PATH from the base image.
# ============================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY pom.xml .
COPY src/ src/

RUN mvn clean package -DskipTests -Dspotless.skip=true -B -q

# Extract Spring Boot layered JAR — optimal Docker layer caching
RUN java -Djarmode=layertools \
    -jar target/url-shortener-*.jar extract \
    --destination target/extracted

# ============================================================
# Stage 2: Runtime
# Minimal JRE image. Non-root user. Only application layers.
# No additional packages installed — image used as-is.
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user
# uid/gid 10001 chosen to avoid conflicts with standard system users
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

# Copy layers in dependency-stability order:
# dependencies (largest, rarely change) → snapshot-dependencies → spring-boot-loader → application
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/application/ ./

# Health check script — uses java.net.http.HttpClient available in the JRE.
# No curl, wget, or any Alpine package required.
COPY --chown=appuser:appgroup docker/healthcheck.java /app/healthcheck.java

USER appuser

# JVM flags for container-awareness:
# -XX:+UseContainerSupport: respect cgroup CPU/memory limits (Java 8u191+, default in 11+)
# -XX:MaxRAMPercentage=75: use 75% of container memory for heap
# -XX:+OptimizeStringConcat: optimise string concatenation
# -Djava.security.egd: faster SecureRandom seeding in containers
# Virtual threads are enabled via application.properties
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+OptimizeStringConcat \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8"

EXPOSE 8080

# Health check: runs healthcheck.java via `java --source 21`.
# JEP 445 unnamed classes (Java 21 preview / Java 23 standard) allow a top-level
# void main() without a class declaration, keeping the script minimal.
# -XX:TieredStopAtLevel=1 skips JIT compilation for this short-lived probe process.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD java -XX:TieredStopAtLevel=1 --enable-preview --source 21 \
        /app/healthcheck.java 2>/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
