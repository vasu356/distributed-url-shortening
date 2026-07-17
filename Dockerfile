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

# ---- Step 1: copy pom.xml --------------------------------
# Separate from src/ so a pom.xml change correctly propagates
# to the Maven build layer, while a src/-only change keeps this
# layer cached.
COPY pom.xml .

# ---- Step 2: compile healthcheck (independent of src/) -----
# Placed before COPY src/ so that changing HealthCheck.java alone
# does NOT invalidate the mvn package layer that follows.
COPY docker/HealthCheck.java ./HealthCheck.java

RUN javac HealthCheck.java -d /build/healthcheck-cls

# ---- Step 3: compile and package the application ----------
# Only reached (and only re-runs Maven) when src/ or pom.xml changes.
# Dependencies are fetched once and cached in /root/.m2 by BuildKit.
# Subsequent builds only download what genuinely changed in pom.xml.
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
#
# WHY eclipse-temurin:21-jre-jammy instead of *-alpine:
# Alpine uses musl libc; it does not provide ld-linux-x86-64.so.2
# (the glibc dynamic linker). kafka-clients' SnappyCodec class
# directly imports org.xerial.snappy.SnappyInputStream/OutputStream
# at the bytecode level. If snappy-java.jar is excluded, the JVM
# throws NoClassDefFoundError when loading SnappyCodec, killing
# every Kafka consumer. If snappy-java.jar is present on Alpine,
# the native libsnappyjava.so cannot load (no ld-linux-x86-64.so.2),
# causing UnsatisfiedLinkError. Ubuntu 22.04 LTS (Jammy) ships glibc
# so the native lib loads correctly and snappy-java works without
# any exclusion or workaround.
# ============================================================
FROM eclipse-temurin:21-jre-jammy AS runtime

# Security: run as non-root user
# uid/gid 10001 chosen to avoid conflicts with standard system users
RUN groupadd -g 10001 appgroup && \
    useradd -u 10001 -g appgroup -s /usr/sbin/nologin -M appuser

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
