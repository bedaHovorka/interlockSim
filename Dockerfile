#
#      Brno University of Technology
#      Faculty of Information Technology
#
#      BSc Thesis       2006/2007
#
#      Railway Interlocking Simulator
#
#      Dockerization: 2025
#
#      Multi-stage build for interlockSim with GUI support
#      Dependency management: Apache Ivy
#

# ============================================
# Stage 1: Build with JDK 6 and Ant
# ============================================
FROM debian:buster-slim AS builder

# Debian Buster is archived - update sources to use archive.debian.org
RUN sed -i 's|http://deb.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i 's|http://security.debian.org|http://archive.debian.org|g' /etc/apt/sources.list && \
    sed -i '/buster-updates/d' /etc/apt/sources.list

# Install OpenJDK 11, Maven, and Ant
RUN apt-get update && apt-get install -y \
    openjdk-11-jdk \
    maven \
    ant \
    && rm -rf /var/lib/apt/lists/*

# Set Java 11 as default but configure compiler for Java 6 compatibility
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Build jDisco dependency first
WORKDIR /build/jdisco
COPY jdisco/ /build/jdisco/
RUN mvn clean install

# Then build interlockSim
WORKDIR /build/interlockSim

# Copy source files and build configuration
COPY src/ /build/interlockSim/src/
COPY build.xml /build/interlockSim/
COPY ivy.xml /build/interlockSim/
COPY ivysettings.xml /build/interlockSim/

# Build the project (compiles code, runs tests)
# Ivy downloads dependencies automatically during resolve phase
RUN ant clean build

# Create JAR with manifest
RUN ant pack

# Verify JAR was created
RUN ls -lh /build/interlockSim/jar/interlockSim.jar && \
    echo "=== JAR Info ===" && \
    jar tf /build/interlockSim/jar/interlockSim.jar | head -20

# ============================================
# Stage 2: Runtime with JRE and X11 support
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
