#
#      Brno University of Technology
#      Faculty of Information Technology
#
#      BSc Thesis       2006/2007
#
#      Railway Interlocking Simulator
#
#      Dockerization: 2025
#      Optimized: 2026-06 (BuildKit apt cache mounts)
#
#      LaTeX thesis compilation with Czech language tools
#

# syntax=docker/dockerfile:1.4

# Global build args (resolved before any stage)
# BUILDKIT_INLINE_CACHE embeds cache metadata in the image for use with --cache-from.
ARG BUILDKIT_INLINE_CACHE

# ============================================
# Stage 1: Install TeX Live and Czech tools
# ============================================
FROM debian:bookworm-slim AS tex-deps

# Keep downloaded .deb packages in the BuildKit apt cache across builds.
RUN rm -f /etc/apt/apt.conf.d/docker-clean \
    && echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' > /etc/apt/apt.conf.d/keep-cache

# Install TeX Live and supporting tools with a persistent apt cache mount.
# The cache mount survives across docker compose builds, so texlive packages
# are downloaded only once (until the package list changes).
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        # Core LaTeX packages
        texlive-latex-base \
        texlive-latex-extra \
        texlive-bibtex-extra \
        texlive-fonts-recommended \
        texlive-font-utils \
        texlive-science \
        # Czech language support
        texlive-lang-czechslovak \
        texlive-binaries \
        # Image conversion tools
        libwmf-bin \
        imagemagick \
        ghostscript \
        # Plot generation
        gnuplot \
        # Build tool
        make \
        # Character encoding tools
        libc-bin

# ============================================
# Stage 2: Build the thesis PDF
# ============================================
FROM tex-deps AS builder

WORKDIR /thesis

# Copy entire text directory (includes img/ and cls/ subdirectories)
COPY text/ /thesis/

# Build the thesis
RUN make pdf && \
    ls -lh /thesis/bakalarka.pdf

# ============================================
# Stage 3: Minimal runtime image with the PDF
# ============================================
FROM debian:bookworm-slim AS runtime

WORKDIR /thesis

# Copy only the compiled PDF from the builder stage.
COPY --from=builder /thesis/bakalarka.pdf /thesis/bakalarka.pdf

# Default command: copy PDF to mounted volume and signal completion
CMD ["sh", "-c", "cp /thesis/bakalarka.pdf /artifacts/ && echo 'Thesis compiled: bakalarka.pdf copied to /artifacts/'"]
