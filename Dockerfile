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
COPY junit.jar /build/interlockSim/

# Patch build.xml to add Java 6 source/target compatibility and ISO-8859-1 encoding
# Note: jDisco library files use ISO-8859-1 encoding
RUN sed -i 's|<javac destdir="build">|<javac destdir="build" source="1.6" target="1.6" encoding="ISO-8859-1" includeantruntime="false">|g' build.xml

# Patch build.xml to add junit.jar to the classpath
RUN sed -i 's|<path id="project.classpath">|<path id="project.classpath">\n        <pathelement location="junit.jar"/>|g' build.xml

# Build the project (compiles code and resources)
RUN ant clean build

# Run tests if they exist
# Note: build.xml doesn't have explicit test target, but tests are in src
RUN ant build

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
COPY --from=builder /build/interlockSim/build/cz/vutbr/fit/interlockSim/resource/ \
                    /app/resource/

# Create artifacts directory for extraction
RUN mkdir -p /artifacts

# Copy JAR to artifacts for host extraction
RUN cp /app/interlockSim.jar /artifacts/

# Environment variables for X11 forwarding
ENV DISPLAY=:0

# Default command: run editor GUI
# Users can override with: docker-compose run app java -ea -jar interlockSim.jar sim file.xml
CMD ["java", "-ea", "-jar", "interlockSim.jar", "edit"]