# syntax=docker/dockerfile:1.7
# ============================================================
# Stage 1: Build
# Official Maven image bundles JDK 21 and Maven 3.9 on Alpine.
# No Maven Wrapper needed — mvn is on PATH from the base image.
#
# BuildKit cache mount on /root/.m2 persists the Maven local
# repository across builds without baking it into any image layer.
# The cache is keyed per-runner and survives image rebuilds.
# ============================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# ---- Step 1: resolve dependencies -------------------------
# Copy only pom.xml first. As long as pom.xml is unchanged this
# layer — and the dependency:go-offline download — is fully cached
# regardless of any source code change.
COPY pom.xml .

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn dependency:go-offline dependency:resolve-plugins -B -q

# ---- Step 2: compile healthcheck (independent of src/) -----
# Placed before COPY src/ so that changing HealthCheck.java alone
# does NOT invalidate the mvn package layer that follows.
COPY docker/HealthCheck.java ./HealthCheck.java

RUN javac HealthCheck.java -d /build/healthcheck-cls

# ---- Step 3: compile and package the application ----------
# Only reached (and only re-runs Maven) when src/ changes.
# All dependencies are already in /root/.m2 from step 1.
COPY src/ src/

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn package -DskipTests -Dspotless.skip=true -B -q

# ---- Step 4: extract Spring Boot layers --------------------
# Splits the fat JAR into stable layers for optimal runtime caching:
#   dependencies        — 3rd-party JARs, rarely change
#   spring-boot-loader  — loader classes, almost never change
#   snapshot-dependencies — SNAPSHOT JARs, change occasionally
#   application         — your code, changes on every commit
RUN java -Djarmode=layertools \
    -jar target/url-shortener-*.jar extract \
    --destination target/extracted

# ============================================================
# Stage 2: Runtime
# Minimal JRE image. Non-root user. Only application layers.
# No JDK, no Maven, no source code, no build tools.
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user
# uid/gid 10001 chosen to avoid conflicts with standard system users
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

# Copy Spring Boot layers in dependency-stability order.
# Layers that change rarely appear first → more cache hits on push/pull.
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/target/extracted/application/ ./

# Copy pre-compiled healthcheck class from builder.
# Compiled in the JDK builder stage so the JRE runtime needs no
# jdk.compiler module. Only the .class file is copied — no source.
COPY --from=builder --chown=appuser:appgroup /build/healthcheck-cls/ /app/healthcheck/

USER appuser

# JVM flags for container-awareness:
# -XX:+UseContainerSupport: respect cgroup CPU/memory limits (default in Java 11+)
# -XX:MaxRAMPercentage=75: use 75% of container memory for heap
# -XX:InitialRAMPercentage=50: start at 50% to avoid slow initial GC
# -XX:+OptimizeStringConcat: optimise string concatenation at JIT time
# -Djava.security.egd: faster SecureRandom seeding in containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+OptimizeStringConcat \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8"

EXPOSE 8080

# Health check: executes the pre-compiled HealthCheck class using the JRE.
# -XX:TieredStopAtLevel=1 skips JIT for this short-lived probe process.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD java -XX:TieredStopAtLevel=1 -cp /app/healthcheck HealthCheck 2>/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
