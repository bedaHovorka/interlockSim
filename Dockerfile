#
#      Brno University of Technology
#      Faculty of Information Technology
#
#      BSc Thesis       2006/2007
#
#      Railway Interlocking Simulator
#
#      Dockerization: 2025
#      Gradle migration: 2026-01
#      Java 21 migration: 2026-01 (Eclipse Temurin + Debian Bookworm)
#
#      Multi-stage build for interlockSim with GUI support
#      Dependency management: Gradle with Kotlin DSL
#      Build system: Java 21 LTS with Eclipse Temurin
#

# syntax=docker/dockerfile:1.4

# ============================================
# Stage 1: Build interlockSim with Gradle
# ============================================
FROM eclipse-temurin:21-jdk AS builder

# Build arguments for GitHub Packages authentication
# These allow Gradle to download jDisco from GitHub Packages
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

WORKDIR /build/interlockSim

# Install git before COPY layers so this layer is cached independently of source changes.
# Moving it here avoids re-downloading git on every gradle.properties / build.gradle.kts bump.
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

# Layer 1: Copy Gradle wrapper files (cached until wrapper version changes)
# These files are checked into git and ensure consistent Gradle version
COPY gradlew /build/interlockSim/
COPY gradlew.bat /build/interlockSim/
COPY gradle/ /build/interlockSim/gradle/
RUN chmod +x gradlew

# Layer 2: Copy build configuration files (cached until config changes)
COPY settings.gradle.kts /build/interlockSim/
COPY gradle.properties /build/interlockSim/
COPY build.gradle.kts /build/interlockSim/
COPY detekt.yml /build/interlockSim/
COPY detekt-strict.yml /build/interlockSim/
COPY .editorconfig /build/interlockSim/

# Layer 2.5: Build kDisco 0.2.0 from source if not in cache
# Mirrors the CI workflow (gradle-java21.yml / sonarqube.yml).
# kDisco 0.2.0 is not published to any public Maven repo, so it must be
# built from source and published to mavenLocal before interlockSim compiles.
# Building kDisco also downloads jDisco (its transitive dep) into the
# Gradle cache; jDisco is then copied to mavenLocal so Layer 5 can resolve
# it as a transitive dep of kDisco without GitHub Packages authentication.
# Requires outbound HTTPS to github.com on cache miss. In air-gapped environments,
# pre-populate the BuildKit cache by running a connected build first.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.m2/repository \
    JDISCO_VERSION="1.2.0"; \
    KDISCO_JAR="/root/.m2/repository/cz/hovorka/kdisco/kdisco-core-api-jvm/0.2.0/kdisco-core-api-jvm-0.2.0.jar"; \
    JDISCO_JAR="/root/.m2/repository/dk/ruc/keld/jdisco/${JDISCO_VERSION}/jdisco-${JDISCO_VERSION}.jar"; \
    JDISCO_POM_M2="/root/.m2/repository/dk/ruc/keld/jdisco/${JDISCO_VERSION}/jdisco-${JDISCO_VERSION}.pom"; \
    if [ ! -f "$KDISCO_JAR" ] || [ ! -f "$JDISCO_JAR" ] || [ ! -f "$JDISCO_POM_M2" ]; then \
        echo "kDisco 0.2.0 or jDisco ${JDISCO_VERSION} not cached — building from source..."; \
        KDISCO_COMMIT="7cb97d0cd5747972775a4719f525a19928faa92b"; \
        git clone https://github.com/bedaHovorka/kdisco.git /tmp/kdisco; \
        cd /tmp/kdisco; \
        git checkout "$KDISCO_COMMIT"; \
        sed -i 's/version[[:space:]]*=[[:space:]]*"0\.2\.0-SNAPSHOT"/version = "0.2.0"/' build.gradle.kts; \
        grep -E 'version[[:space:]]*=[[:space:]]*"0\.2\.0"' build.gradle.kts; \
        GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
        ./gradlew :kdisco-core-api:publishToMavenLocal --no-daemon; \
        rm -rf /tmp/kdisco; \
        JDISCO_DIR="/root/.m2/repository/dk/ruc/keld/jdisco/${JDISCO_VERSION}"; \
        mkdir -p "$JDISCO_DIR"; \
        JDISCO_CACHE=$(find /root/.gradle/caches/modules-2 -name "jdisco-${JDISCO_VERSION}.jar" 2>/dev/null | head -1); \
        JDISCO_POM=$(find /root/.gradle/caches/modules-2 -name "jdisco-${JDISCO_VERSION}.pom" 2>/dev/null | head -1); \
        [ -n "$JDISCO_CACHE" ] || { echo "ERROR: jdisco-${JDISCO_VERSION}.jar not found in Gradle cache"; exit 1; }; \
        [ -n "$JDISCO_POM" ]   || { echo "ERROR: jdisco-${JDISCO_VERSION}.pom not found in Gradle cache"; exit 1; }; \
        cp "$JDISCO_CACHE" "$JDISCO_DIR/jdisco-${JDISCO_VERSION}.jar" || { echo "ERROR: failed to copy jdisco-${JDISCO_VERSION}.jar into mavenLocal at $JDISCO_DIR"; exit 1; }; \
        cp "$JDISCO_POM"   "$JDISCO_DIR/jdisco-${JDISCO_VERSION}.pom" || { echo "ERROR: failed to copy jdisco-${JDISCO_VERSION}.pom into mavenLocal at $JDISCO_DIR"; exit 1; }; \
        [ -f "$JDISCO_DIR/jdisco-${JDISCO_VERSION}.jar" ] || { echo "ERROR: jdisco-${JDISCO_VERSION}.jar not present in mavenLocal at $JDISCO_DIR after copy"; exit 1; }; \
        [ -f "$JDISCO_DIR/jdisco-${JDISCO_VERSION}.pom" ] || { echo "ERROR: jdisco-${JDISCO_VERSION}.pom not present in mavenLocal at $JDISCO_DIR after copy"; exit 1; }; \
        echo "jDisco ${JDISCO_VERSION} JAR and POM installed to mavenLocal at $JDISCO_DIR"; \
    else \
        echo "kDisco 0.2.0 and jDisco ${JDISCO_VERSION} found in cache — skipping build"; \
    fi

# Layer 3: Resolve dependencies with BuildKit cache mount
# Gradle caches: dependencies, build cache, and Gradle distributions
# kDisco 0.2.0 and jDisco are now available via mavenLocal / Gradle cache
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.m2/repository \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew dependencies --no-daemon --warning-mode=summary

# Layer 4: Copy source code
# This is the layer that changes most frequently
COPY src/ /build/interlockSim/src/

# Layer 5: Build and test with cache mount
# Tests run during build (haltOnFailure), creating uber JAR with shadowJar
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.m2/repository \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew clean build shadowJar --no-daemon --warning-mode=summary

# Verify JAR was created
RUN ls -lh /build/interlockSim/build/libs/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/build/libs/interlockSim.jar | head -20

# ============================================
# Stage 2: Runtime with JRE and X11 support
# ============================================
FROM eclipse-temurin:21-jre AS runner

# Install X11 libraries for GUI support
# Eclipse Temurin already includes Java 21 JRE
RUN apt-get update && apt-get install -y \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxrandr2 \
    libxcursor1 \
    libxinerama1 \
    fontconfig \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy compiled uber JAR from builder stage (Gradle output)
COPY --from=builder /build/interlockSim/build/libs/interlockSim.jar /app/

# Copy resources if needed at runtime (XML schemas, examples)
COPY --from=builder /build/interlockSim/build/resources/main/cz/vutbr/fit/interlockSim/resource/ \
                    /app/resource/

# Create artifacts directory for extraction
RUN mkdir -p /artifacts

# Copy JAR to artifacts for host extraction
RUN cp /app/interlockSim.jar /artifacts/

# Environment variables for X11 forwarding
ENV DISPLAY=:0

# Default command: run editor GUI
# Users can override with: docker compose run app java -jar interlockSim.jar sim file.xml
CMD ["java", "-ea", "-jar", "interlockSim.jar", "edit"]
