#
#      Brno University of Technology
#      Faculty of Information Technology
#
#      BSc Thesis       2006/2007
#
#      Railway Interlocking Simulator
#
#      Dockerization: 2025
#      Optimized: 2026-01 (BuildKit cache mounts, layer optimization)
#
#      Multi-stage build for interlockSim with GUI support
#      Dependency management: Apache Ivy
#

# syntax=docker/dockerfile:1.4

# ============================================
# Stage 1: Reference pre-built jDisco image
# ============================================
# Build jdisco separately: docker compose build jdisco
# This avoids duplicating jdisco/Dockerfile logic here
FROM jdisco:latest AS jdisco-builder

# ============================================
# Stage 2: Build interlockSim
# ============================================
FROM debian:buster-slim AS builder

# Debian Buster is archived - update sources
RUN sed -i 's|http://deb.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i 's|http://security.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i '/buster-updates/d' /etc/apt/sources.list

# Install build tools
RUN apt-get update && apt-get install -y \
    openjdk-11-jdk \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Install Apache Ant 1.10.14
RUN wget -q https://archive.apache.org/dist/ant/binaries/apache-ant-1.10.14-bin.zip && \
    unzip -q apache-ant-1.10.14-bin.zip && \
    mv apache-ant-1.10.14 /opt/ant && \
    rm apache-ant-1.10.14-bin.zip

ENV ANT_HOME=/opt/ant
ENV PATH=$ANT_HOME/bin:$PATH

WORKDIR /build/interlockSim

# Copy jDisco from previous stage
COPY --from=jdisco-builder /root/.m2/repository/ /root/.m2/repository/

# Layer 1: Copy ONLY dependency metadata files
# This layer caches unless these files change
COPY build.xml /build/interlockSim/
COPY ivy.xml /build/interlockSim/
COPY ivysettings.xml /build/interlockSim/

# Layer 2: Resolve Ivy dependencies with BuildKit cache mount
# This layer caches dependencies across builds
RUN --mount=type=cache,target=/root/.ivy2 \
    ant resolve

# Layer 3: Copy source code
# This is the layer that changes most frequently
COPY src/ /build/interlockSim/src/

# Layer 4: Build and test
# Ivy cache persists, so no re-downloads
RUN --mount=type=cache,target=/root/.ivy2 \
    ant clean build

# Create JAR with manifest
RUN ant pack

# Verify JAR was created
RUN ls -lh /build/interlockSim/jar/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/jar/interlockSim.jar | head -20

# ============================================
# Stage 3: Runtime with JRE and X11 support
# ============================================
FROM debian:buster-slim AS runner

# Debian Buster is archived - update sources to use archive.debian.org
RUN sed -i 's|http://deb.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i 's|http://security.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i '/buster-updates/d' /etc/apt/sources.list

# Install OpenJDK 11 JRE and X11 libraries for GUI support
RUN apt-get update && apt-get install -y \
    openjdk-11-jre \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxrandr2 \
    libxcursor1 \
    libxinerama1 \
    libxfixes3 \
    libxdamage1 \
    libxcomposite1 \
    libfreetype6 \
    libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

# Set Java 11 JRE as default
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /app

# Copy compiled JAR from builder stage
COPY --from=builder /build/interlockSim/jar/interlockSim.jar /app/

# Copy resources if needed at runtime (XML schemas, examples)
COPY --from=builder /build/interlockSim/build/main/cz/vutbr/fit/interlockSim/resource/ \
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
