# syntax=docker/dockerfile:1.4
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

# ============================================
# Stage 1: Build interlockSim with Gradle
# ============================================
FROM eclipse-temurin:21-jdk-noble AS builder

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
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2-v2,uid=1001,gid=1001 \
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

# Layer 5: Compile and package with cache mount (no tests).
# Tests are decoupled from the app image build and run in the separate
# `test-runner` stage below. This means `docker compose build app` always
# produces the image even when a test suite is temporarily broken, which
# mirrors how CI works (compile/assemble and test are separate steps).
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2-v2,uid=1001,gid=1001 \
    --mount=type=secret,id=github_actor,uid=1001,gid=1001,mode=0400 \
    --mount=type=secret,id=github_token,uid=1001,gid=1001,mode=0400 \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew clean shadowJar --no-daemon --warning-mode=summary

# Verify JAR was created
RUN ls -lh /build/interlockSim/desktop-ui/build/libs/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/desktop-ui/build/libs/interlockSim.jar | head -20

# ============================================
# Stage 1.5: Test runner (separate from app image build)
# ============================================
# Build this target to execute the full test suite independently of the
# app image. Tests run DURING the image build (there is no runtime test
# entry point -- BuildKit cache mounts and secrets only exist at build time):
#   docker compose --profile test build test
#
# This stage extends the `builder` image, reusing its dependency cache and
# main-source compilation; test sources and the Kotlin/Native target are
# compiled here. Tests run as the non-root `builder` user (UID 1001), which
# is required for filesystem-permission tests (root bypasses DAC checks).
FROM builder AS test-runner

# Layer T1: Run the full test suite and quality gates using the compiled
# sources and cached Gradle dependencies from the builder stage.
# `check` covers what the old `build`-based Layer 5 enforced: unit tests,
# detekt, ktlintCheck, and :core:checkCoreCommonMainPurity.
RUN --mount=type=cache,target=/home/builder/.gradle/caches,id=app-gradle-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.gradle/wrapper,id=app-wrapper-v2,uid=1001,gid=1001 \
    --mount=type=cache,target=/home/builder/.m2/repository,id=app-m2-v2,uid=1001,gid=1001 \
    --mount=type=secret,id=github_actor,uid=1001,gid=1001,mode=0400 \
    --mount=type=secret,id=github_token,uid=1001,gid=1001,mode=0400 \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew check integrationTest :core:allTests --no-daemon --warning-mode=summary

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

# The runtime stage runs as a non-root 'app' user whose UID/GID is set at
# build time via RUNTIME_UID / RUNTIME_GID ARGs (default 1000/1000).
# Passing the host user's UID/GID (e.g. via docker-compose.yml build args)
# creates a container user whose UID matches the host user, so the 0600
# X11 auth cookie bind-mounted from the host is readable without root.
# This is the standard matching-UID pattern for X11-forwarding dev containers.
# It is also safer than root: bind-mounted host directories (e.g.
# ./artifacts/app) are written with host-user ownership, not root.
#
# The builder stage still MUST run as non-root because the test suite
# includes filesystem-permission tests that are auto-skipped under root (e.g.
# @DisabledIfSystemProperty(matches = "root")). Running tests as root would
# silently skip coverage.
# See PR #620 (non-root build) and issue #624 (matching-UID runtime).

ARG RUNTIME_UID=1000
ARG RUNTIME_GID=1000

# Create a non-root 'app' group and user with the target UID/GID.
# Guards handle the case where the base image already has an entry at that
# UID/GID (e.g. the 'ubuntu' user at UID 1000 in eclipse-temurin:21-jre-noble).
RUN (getent group ${RUNTIME_GID} || groupadd --gid ${RUNTIME_GID} app) \
    && (getent passwd ${RUNTIME_UID} || useradd --uid ${RUNTIME_UID} --gid ${RUNTIME_GID} \
        --no-create-home --home-dir /app --shell /bin/sh app)

WORKDIR /app

# Copy compiled uber JAR from builder stage (Gradle output)
COPY --from=builder /build/interlockSim/desktop-ui/build/libs/interlockSim.jar /app/

# Copy resources if needed at runtime (XML schemas, examples)
COPY --from=builder /build/interlockSim/desktop-ui/build/resources/main/cz/vutbr/fit/interlockSim/resource/ \
                    /app/resource/

# Create artifacts directory, copy JAR for host extraction, and transfer
# ownership of /artifacts and /app to the app user so files are accessible
# and writable at runtime. /app must be writable in the opt-in case
# (RUNTIME_UID != 1000) where useradd sets --home-dir /app; without this
# the app user cannot write caches/preferences to its own $HOME and
# Swing/fontconfig may fail to persist.
RUN mkdir -p /artifacts && cp /app/interlockSim.jar /artifacts/ \
    && chown -R ${RUNTIME_UID}:${RUNTIME_GID} /artifacts \
    && chown -R ${RUNTIME_UID}:${RUNTIME_GID} /app

USER ${RUNTIME_UID}:${RUNTIME_GID}

# Environment variables for X11 forwarding
ENV DISPLAY=:0

# Default command: run editor GUI
# Users can override with: docker compose run app java -jar interlockSim.jar sim file.xml
CMD ["java", "-ea", "-jar", "interlockSim.jar", "edit"]
