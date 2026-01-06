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
# Stage 1: Reference pre-built jDisco image
# ============================================
# Build jdisco separately: docker compose build jdisco
# This avoids duplicating jdisco/Dockerfile logic here
FROM jdisco:latest AS jdisco-builder

# ============================================
# Stage 2: Build interlockSim with Gradle
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build/interlockSim

# Copy jDisco from previous stage (required dependency)
COPY --from=jdisco-builder /root/.m2/repository/ /root/.m2/repository/

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

# Layer 3: Resolve dependencies with BuildKit cache mount
# Gradle caches: dependencies, build cache, and Gradle distributions
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew dependencies --no-daemon --warning-mode=summary

# Layer 4: Copy source code
# This is the layer that changes most frequently
COPY src/ /build/interlockSim/src/

# Layer 5: Build and test with cache mount
# Tests run during build (haltOnFailure), creating uber JAR with shadowJar
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew clean build shadowJar --no-daemon --warning-mode=summary

# Verify JAR was created
RUN ls -lh /build/interlockSim/build/libs/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/build/libs/interlockSim.jar | head -20

# ============================================
# Stage 3: Runtime with JRE and X11 support
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
# Users can override with: docker compose run app java -ea -jar interlockSim.jar sim file.xml
CMD ["java", "-ea", "-jar", "interlockSim.jar", "edit"]
