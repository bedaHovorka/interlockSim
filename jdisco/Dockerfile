#
#      jDisco - Discrete/Continuous Simulation Library
#
#      Originally written by Keld Helsgaun (2001-2004)
#      Roskilde University, Denmark
#
#      Dockerization: 2025
#      Optimized: 2026-01 (BuildKit cache mounts)
#
#      Maven build environment with JDK 6 compatibility
#

# syntax=docker/dockerfile:1.4

# Use Debian Buster with OpenJDK 11 + Maven
# Java 11 can compile to Java 6 bytecode with -source 1.6 -target 1.6
FROM debian:buster-slim

# Debian Buster is archived - update sources
RUN sed -i 's|http://deb.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i 's|http://security.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i '/buster-updates/d' /etc/apt/sources.list

# Install OpenJDK 11 and Maven
RUN apt-get update && apt-get install -y \
    openjdk-11-jdk \
    maven \
    && rm -rf /var/lib/apt/lists/*

# Set Java environment
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /build/jdisco

# Layer 1: Copy ONLY dependency metadata
# This layer caches unless pom.xml changes
COPY pom.xml /build/jdisco/

# Layer 2: Download dependencies with BuildKit cache mount
# Maven local repository persists across builds
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Layer 3: Copy source code
# This layer invalidates ONLY if source changes
COPY src/ /build/jdisco/src/

# Layer 4: Build, test, and package with BuildKit cache mount
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean compile test package

# Layer 5: Install to local repository (without cache mount so it persists)
RUN mvn install

# Create artifacts directory
RUN mkdir -p /artifacts

# Copy built artifacts for extraction
RUN cp target/*.jar /artifacts/ || true

# Verify build
RUN ls -lh /artifacts/ && \
    echo "=== Build Complete ===" && \
    mvn --version && \
    java -version

# Default command: show help
CMD ["mvn", "--help"]
