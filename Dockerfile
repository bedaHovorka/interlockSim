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
#      Java 21 migration: 2026-01 (Eclipse Temurin + Ubuntu 24.04 LTS Noble)
#
#      Multi-stage build for interlockSim with GUI support
#      Dependency management: Gradle with Kotlin DSL
#      Build system: Java 21 LTS with Eclipse Temurin
#

# syntax=docker/dockerfile:1.4

# ============================================
# Stage 1a: Gradle build environment (non-root user)
# ============================================
FROM eclipse-temurin:21-jdk-noble AS builder-base

# GitHub Packages authentication is provided at build time via BuildKit secrets
# (see docker-compose.yml secrets: github_actor / github_token). The credentials
# are mounted under /run/secrets/ and exported as environment variables only
# inside the RUN steps that invoke Gradle. They are never interpolated into the
# Dockerfile command string, so they cannot leak into image history or build logs.

WORKDIR /build/interlockSim

# Install git before COPY layers so this layer is cached independently of source changes.
# Moving it here avoids re-downloading git on every gradle.properties / build.gradle.kts bump.
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends git libxml2-dev libicu-dev && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/include/unicode /usr/include/libxml2/unicode

# Create an unprivileged builder user so the build and tests run as a normal user.
# Cache mounts, Gradle home, and the project directory are owned by this user.
# Use UID/GID 1001 to avoid colliding with the base image's ubuntu group (1000).
ARG BUILDER_UID=1001
ARG BUILDER_GID=1001
RUN (getent group ${BUILDER_GID} || groupadd --gid ${BUILDER_GID} builder) \
    && (getent passwd builder || useradd --uid ${BUILDER_UID} --gid ${BUILDER_GID} --shell /bin/bash --create-home builder) \
    && mkdir -p /home/builder/.gradle /home/builder/.m2 \
    && chown -R builder:builder /build /home/builder

ENV HOME=/home/builder
ENV GRADLE_USER_HOME=/home/builder/.gradle

# Layer 1: Copy Gradle wrapper files (cached until wrapper version changes)
# These files are checked into git and ensure consistent Gradle version
COPY --chown=builder:builder gradlew /build/interlockSim/
COPY --chown=builder:builder gradlew.bat /build/interlockSim/
COPY --chown=builder:builder gradle/ /build/interlockSim/gradle/
RUN chmod +x gradlew

# Layer 2: Copy build configuration files (cached until config changes)
COPY --chown=builder:builder settings.gradle.kts /build/interlockSim/
COPY --chown=builder:builder gradle.properties /build/interlockSim/
COPY --chown=builder:builder build.gradle.kts /build/interlockSim/
COPY --chown=builder:builder detekt.yml /build/interlockSim/
COPY --chown=builder:builder detekt-strict.yml /build/interlockSim/
COPY --chown=builder:builder .editorconfig /build/interlockSim/
COPY --chown=builder:builder core/build.gradle.kts /build/interlockSim/core/
COPY --chown=builder:builder core-test/build.gradle.kts /build/interlockSim/core-test/
COPY --chown=builder:builder desktop-ui/build.gradle.kts /build/interlockSim/desktop-ui/

# All subsequent Gradle commands run as the builder user.
USER builder

# Layer 2.5: kDisco is fetched from GitHub Packages during dependency resolution.
# Credentials come from BuildKit secret mounts, not from ARG, so the token is
# never visible in image history or in BuildKit's resolved RUN command output.
# BuildKit cache mount at /home/builder/.gradle and /home/builder/.m2 keeps
# downloaded artifacts warm. The cache mounts are owned by the builder user.

# Layer 3: Resolve dependencies with BuildKit cache mount and GitHub Packages authentication.
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2,uid=1001,gid=1001 \
    --mount=type=secret,id=github_actor,uid=1001,gid=1001,mode=0400 \
    --mount=type=secret,id=github_token,uid=1001,gid=1001,mode=0400 \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew dependencies --no-daemon --warning-mode=summary

# Layer 4: Copy source code
# This is the layer that changes most frequently
COPY --chown=builder:builder desktop-ui/src/ /build/interlockSim/desktop-ui/src/
COPY --chown=builder:builder core/src/ /build/interlockSim/core/src/
COPY --chown=builder:builder core-test/src/ /build/interlockSim/core-test/src/

# ============================================
# Stage 1b: Produce runtime artifacts (no tests)
# ============================================
FROM builder-base AS builder

# Build the application and uber JAR without running tests. Tests are executed
# in the separate builder-test stage / app-test Compose service (issue #619).
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2,uid=1001,gid=1001 \
    --mount=type=secret,id=github_actor,uid=1001,gid=1001,mode=0400 \
    --mount=type=secret,id=github_token,uid=1001,gid=1001,mode=0400 \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew clean assemble shadowJar --no-daemon --warning-mode=summary

# Verify JAR was created
USER root
RUN ls -lh /build/interlockSim/desktop-ui/build/libs/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/desktop-ui/build/libs/interlockSim.jar | head -20

# ============================================
# Stage 1c: Test environment (non-root user, tests enabled)
# ============================================
FROM builder-base AS builder-test

# Run the full test suite as the non-root builder user. Kept as a separate
# stage so docker compose run app-test can execute tests without coupling them
# to the runtime image build.
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2,uid=1001,gid=1001 \
    --mount=type=secret,id=github_actor,uid=1001,gid=1001,mode=0400 \
    --mount=type=secret,id=github_token,uid=1001,gid=1001,mode=0400 \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew clean test integrationTest :core:linuxX64Test --no-daemon --warning-mode=summary

# ============================================
# Stage 2: Runtime with JRE and X11 support
# ============================================
FROM eclipse-temurin:21-jre-noble AS runner

# Install X11 libraries for GUI support
# Eclipse Temurin already includes Java 21 JRE
RUN apt-get update && apt-get upgrade -y && apt-get install -y \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxrandr2 \
    libxcursor1 \
    libxinerama1 \
    fontconfig \
    && rm -rf /var/lib/apt/lists/*

# The runtime stage runs as root, intentionally -- unlike the builder stage,
# which runs as the non-root `builder` user. The split is deliberate:
#   * The builder stage MUST be non-root: the test suite includes filesystem
#     permission tests that are auto-skipped under root (e.g.
#     @DisabledIfSystemProperty(matches = "root")), because root bypasses write
#     permissions and cannot exercise them. Running tests as root would silently
#     skip coverage.
#   * The runtime stage only launches the app, so it has no such constraint, and
#     root is required for GUI/X11 forwarding: the host X11 auth cookie is bind
#     -mounted read-only (mode 0600, owned by the host user), so a non-root
#     container user cannot read it and Swing fails with "Can't connect to X11".
# See PR #620 (non-root build) and the X11 regression fix that followed.
WORKDIR /app

# Copy compiled uber JAR from builder stage (Gradle output)
COPY --from=builder /build/interlockSim/desktop-ui/build/libs/interlockSim.jar /app/

# Copy resources if needed at runtime (XML schemas, examples)
COPY --from=builder /build/interlockSim/desktop-ui/build/resources/main/cz/vutbr/fit/interlockSim/resource/ \
                    /app/resource/

# Create artifacts directory and copy JAR for host extraction
RUN mkdir -p /artifacts && cp /app/interlockSim.jar /artifacts/

# Environment variables for X11 forwarding
ENV DISPLAY=:0

# Default command: run editor GUI
# Users can override with: docker compose run app java -jar interlockSim.jar sim file.xml
CMD ["java", "-ea", "-jar", "interlockSim.jar", "edit"]
