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
#      LaTeX thesis compilation with Czech language tools
#

FROM debian:bookworm-slim

# Install TeX Live and Czech language tools
RUN apt-get update && apt-get install -y \
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
    libc-bin \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /thesis

# Copy entire text directory (includes img/ and cls/ subdirectories)
COPY text/ /thesis/

# Build the thesis
RUN make pdf && \
    ls -lh /thesis/bakalarka.pdf

# Default command: copy PDF to mounted volume and signal completion
CMD ["sh", "-c", "cp bakalarka.pdf /artifacts/ && echo 'Thesis compiled: bakalarka.pdf copied to /artifacts/'"]