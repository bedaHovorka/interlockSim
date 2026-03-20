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
# Required to download kdisco-core-jvm from GitHub Packages (bedaHovorka/kdisco)
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
COPY core/build.gradle.kts /build/interlockSim/core/
COPY desktop-ui/build.gradle.kts /build/interlockSim/desktop-ui/

# Layer 2.5: Pre-stage kdisco-core-jvm:0.3.0-SNAPSHOT artifacts
# Copied to /root/kdisco-prebuild/ (not /root/.m2/) because the build-step
# cache mount at /root/.m2/repository would shadow a direct COPY there.
# The RUN step below installs from this location into the mounted mavenLocal.
# To refresh: re-run `./gradlew :kdisco-core:publishToMavenLocal` in kdisco
# and copy the output to docker-kdisco/ in this repo.
COPY docker-kdisco/ /root/kdisco-prebuild/

# Layer 3: Resolve dependencies with BuildKit cache mount
# Install pre-staged kdisco into mounted mavenLocal, then resolve all deps.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.m2/repository \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew dependencies --no-daemon --warning-mode=summary

# Layer 4: Copy source code
# This is the layer that changes most frequently
COPY desktop-ui/src/ /build/interlockSim/desktop-ui/src/
COPY core/src/ /build/interlockSim/core/src/

# Layer 5: Build and test with cache mount
# Tests run during build (haltOnFailure), creating uber JAR with shadowJar
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.m2/repository \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew clean build shadowJar --no-daemon --warning-mode=summary

# Verify JAR was created
RUN ls -lh /build/interlockSim/desktop-ui/build/libs/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/desktop-ui/build/libs/interlockSim.jar | head -20

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
COPY --from=builder /build/interlockSim/desktop-ui/build/libs/interlockSim.jar /app/

# Copy resources if needed at runtime (XML schemas, examples)
COPY --from=builder /build/interlockSim/desktop-ui/build/resources/main/cz/vutbr/fit/interlockSim/resource/ \
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
