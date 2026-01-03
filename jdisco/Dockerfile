#
#      jDisco - Discrete/Continuous Simulation Library
#
#      Originally written by Keld Helsgaun (2001-2004)
#      Roskilde University, Denmark
#
#      Dockerization: 2025
#
#      Maven build environment with JDK 6 compatibility
#

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

# Copy Maven configuration
COPY pom.xml /build/jdisco/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B || true

# Copy source code
COPY src/ /build/jdisco/src/

# Build the project
RUN mvn clean compile

# Run tests
RUN mvn test

# Package JAR
RUN mvn package

# Install to local repository
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
